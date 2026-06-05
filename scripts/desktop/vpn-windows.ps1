<#
  Privileged Windows VPN wrapper for the OnthecrowVPN desktop app.
  Runs elevated (UAC), launched via `Start-Process -Verb RunAs`.

  The libXray sidecar itself creates the Wintun adapter and (via route_windows.go /
  dns_windows.go) assigns addresses, default routes and DNS. This wrapper only:
    1. Excludes the proxy server IP from the tunnel (host route via the real
       gateway) so Xray's own uplink doesn't loop back into the TUN.
    2. Starts the sidecar, waits for the adapter, signals readiness.
    3. Waits for a stop sentinel and reverts the exclusion route on exit.

  Args (positional): Sidecar RunJson TunName ServerHost WorkDir
#>
param(
    [Parameter(Mandatory = $true)][string]$Sidecar,
    [Parameter(Mandatory = $true)][string]$RunJson,
    [Parameter(Mandatory = $true)][string]$TunName,
    [Parameter(Mandatory = $true)][string]$ServerHost,
    [Parameter(Mandatory = $true)][string]$WorkDir
)

$ErrorActionPreference = 'Stop'
$Log   = Join-Path $WorkDir 'sidecar.log'
$Ready = Join-Path $WorkDir 'ready'
$Stop  = Join-Path $WorkDir 'stop'
$ErrF  = Join-Path $WorkDir 'error'

$script:Proc = $null
$script:ServerIp = $null

function Write-Log($msg) { Add-Content -Path $Log -Value "[vpn-windows] $msg" }

function Cleanup {
    Write-Log 'cleanup start'
    if ($script:Proc -and -not $script:Proc.HasExited) {
        try { Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue } catch {}
    }
    if ($script:ServerIp) {
        try { Remove-NetRoute -DestinationPrefix "$($script:ServerIp)/32" -Confirm:$false -ErrorAction SilentlyContinue } catch {}
        cmd /c "route delete $($script:ServerIp)" 2>$null | Out-Null
    }
    Remove-Item -Path $Ready -ErrorAction SilentlyContinue
    Write-Log 'cleanup done'
}

function Fail($msg) {
    Write-Log "ERROR: $msg"
    Set-Content -Path $ErrF -Value $msg
    Cleanup
    exit 1
}

try {
    New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
    Set-Content -Path $Log -Value ''
    Remove-Item -Path $Ready, $Stop, $ErrF -ErrorAction SilentlyContinue

    # 1. Physical default route (gateway + interface index).
    $def = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric | Select-Object -First 1
    if (-not $def) { Fail 'no default route found' }
    $gw = $def.NextHop
    $physIdx = $def.InterfaceIndex
    Write-Log "default gateway=$gw ifIndex=$physIdx"

    # 2. Resolve server host -> IPv4.
    if ($ServerHost -match '^\d{1,3}(\.\d{1,3}){3}$') {
        $script:ServerIp = $ServerHost
    } else {
        $script:ServerIp = ([System.Net.Dns]::GetHostAddresses($ServerHost) |
            Where-Object { $_.AddressFamily -eq 'InterNetwork' } |
            Select-Object -First 1).IPAddressToString
    }
    if (-not $script:ServerIp) { Fail "could not resolve server host $ServerHost" }
    Write-Log "server $ServerHost -> $($script:ServerIp)"

    # 3. Exclude server from the tunnel (most-specific /32 host route wins).
    try {
        New-NetRoute -DestinationPrefix "$($script:ServerIp)/32" -InterfaceIndex $physIdx `
            -NextHop $gw -RouteMetric 1 -ErrorAction Stop | Out-Null
    } catch {
        cmd /c "route add $($script:ServerIp) mask 255.255.255.255 $gw metric 1" | Out-Null
    }

    # 4. Start the sidecar (creates Wintun adapter + routes + DNS internally).
    $script:Proc = Start-Process -FilePath $Sidecar -ArgumentList '-configPath', $RunJson `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $Log -RedirectStandardError (Join-Path $WorkDir 'sidecar.err.log')
    Write-Log "sidecar pid=$($script:Proc.Id)"

    # 5. Wait for the Wintun adapter to come up.
    $ok = $false
    for ($i = 0; $i -lt 50; $i++) {
        if (Get-NetAdapter -Name $TunName -ErrorAction SilentlyContinue) { $ok = $true; break }
        if ($script:Proc.HasExited) { Fail "sidecar exited before adapter $TunName appeared" }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ok) { Fail "adapter $TunName did not appear" }
    Write-Log "$TunName is up"

    # 6. Ready, then wait for the stop sentinel.
    New-Item -ItemType File -Path $Ready -Force | Out-Null
    Write-Log 'ready'
    while (-not (Test-Path $Stop)) {
        if ($script:Proc.HasExited) { Write-Log 'sidecar died unexpectedly'; break }
        Start-Sleep -Milliseconds 500
    }

    Cleanup
    exit 0
} catch {
    Fail $_.Exception.Message
}
