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
# $Log is the sidecar's stdout redirect target ONLY. The sidecar holds it open with an
# exclusive write handle for its whole lifetime, so the wrapper must NOT write to it -
# Add-Content against an open redirect target throws IOException on Windows. The wrapper's
# own diagnostics go to a separate $WrapLog instead.
$Log     = Join-Path $WorkDir 'sidecar.log'
$WrapLog = Join-Path $WorkDir 'wrapper.log'
$Ready = Join-Path $WorkDir 'ready'
$Stop  = Join-Path $WorkDir 'stop'
$ErrF  = Join-Path $WorkDir 'error'

$script:Proc = $null
$script:ServerIp = $null
$script:TunIdx = $null
$SplitHalves = @('0.0.0.0/1', '128.0.0.0/1')

# Logging is best-effort: a log write must never abort the session (it previously cascaded
# through Fail and left neither a `ready` nor an `error` sentinel).
function Write-Log($msg) { Add-Content -Path $WrapLog -Value "[vpn-windows] $msg" -ErrorAction SilentlyContinue }

function Cleanup {
    # Teardown must NEVER throw. A failure here used to bubble to the outer catch -> Fail,
    # which wrote a spurious `error` sentinel (and re-entered Cleanup) on a perfectly clean
    # disconnect - Remove-NetRoute raises a terminating error for an already-gone route that
    # -ErrorAction can't fully suppress, so force the whole function non-terminating.
    $local:ErrorActionPreference = 'SilentlyContinue'
    Write-Log 'cleanup start'
    Remove-Item -Path $Ready -ErrorAction SilentlyContinue   # always clear `ready` first
    if ($script:Proc -and -not $script:Proc.HasExited) {
        try { Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue } catch {}
    }
    if ($script:ServerIp) {
        try { Remove-NetRoute -DestinationPrefix "$($script:ServerIp)/32" -Confirm:$false -ErrorAction SilentlyContinue } catch {}
        cmd /c "route delete $($script:ServerIp)" 2>$null | Out-Null
    }
    # Remove the full-tunnel split routes (they usually vanish with the adapter, but be explicit).
    foreach ($half in $SplitHalves) {
        try { Remove-NetRoute -DestinationPrefix $half -Confirm:$false -ErrorAction SilentlyContinue } catch {}
        cmd /c "route delete $($half.Split('/')[0]) mask 128.0.0.0" 2>$null | Out-Null
    }
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
    Set-Content -Path $WrapLog -Value '' -ErrorAction SilentlyContinue
    Remove-Item -Path $Ready, $Stop, $ErrF -ErrorAction SilentlyContinue

    # 0. Defensive teardown of any PREVIOUS session still alive. On a config switch the JVM may not
    # have fully stopped the old elevated sidecar; while its Wintun adapter is still up, our new sidecar
    # cannot create the adapter ("Failed to register rings") and every start attempt dies. We run
    # elevated, so kill any stray onthecrow-xray and wait for the old adapter to disappear before
    # starting ours. (Our own sidecar is not started until step 4, so this only hits a leftover.)
    $stray = Get-Process -Name 'onthecrow-xray' -ErrorAction SilentlyContinue
    if ($stray) {
        Write-Log "killing $($stray.Count) stray sidecar process(es) from a previous session"
        $stray | Stop-Process -Force -ErrorAction SilentlyContinue
        # Wait for the PROCESS to actually exit (that releases the Wintun adapter's rings); the adapter
        # object itself may linger a moment longer, so add a short settle before we create our own.
        for ($i = 0; $i -lt 40; $i++) {
            if (-not (Get-Process -Name 'onthecrow-xray' -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Milliseconds 200
        }
        Start-Sleep -Milliseconds 1000
        Write-Log "stray sidecar cleared"
    }

    # 1. Find the PHYSICAL default route (gateway + interface index). The server-exclusion /32
    # must be anchored to a real uplink, NOT another VPN tunnel (e.g. a coexisting client): if it
    # went via a tunnel's gateway and that tunnel later dropped, the sidecar would lose its server
    # uplink and - because we full-tunnel - ALL traffic would black-hole. Skip our own tun and any
    # virtual tunnel adapter; fall back to the lowest-metric default only if nothing else exists.
    $defs = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object RouteMetric
    $def = $defs | Where-Object {
        $a = Get-NetAdapter -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue
        $a -and $a.Name -ne $TunName -and
        $a.InterfaceDescription -notmatch 'Wintun|sing-tun|WireGuard|TAP|TUN|VPN|Tunnel'
    } | Select-Object -First 1
    if (-not $def) { $def = $defs | Select-Object -First 1 }
    if (-not $def) { Fail 'no default route found' }
    $gw = $def.NextHop
    $physIdx = $def.InterfaceIndex
    $physDesc = (Get-NetAdapter -InterfaceIndex $physIdx -ErrorAction SilentlyContinue).InterfaceDescription
    Write-Log "physical default gateway=$gw ifIndex=$physIdx ($physDesc)"

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

    # 4 + 5. Start the sidecar and wait for the Wintun adapter, retrying the whole step on failure.
    # The libXray runner intermittently dies at startup with "netsh add ipv6 address failed" - a race
    # between creating the Wintun adapter and configuring it - so a single attempt is unreliable; a
    # restart almost always succeeds. A freshly created adapter can also be briefly visible by name
    # without an ifIndex yet, so we wait for a real index. Everything below keys off the captured
    # $script:TunIdx (stable) rather than re-resolving by name (which can transiently miss).
    $errLog = Join-Path $WorkDir 'sidecar.err.log'
    $script:TunIdx = $null
    $maxAttempts = 6
    for ($a = 1; $a -le $maxAttempts; $a++) {
        $script:Proc = Start-Process -FilePath $Sidecar -ArgumentList '-configPath', $RunJson `
            -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $Log -RedirectStandardError $errLog
        Write-Log "sidecar pid=$($script:Proc.Id) (attempt $a/$maxAttempts)"
        $idx = $null
        for ($i = 0; $i -lt 50; $i++) {
            $ad = Get-NetAdapter -Name $TunName -ErrorAction SilentlyContinue
            if ($ad -and $ad.ifIndex) { $idx = $ad.ifIndex; break }
            if ($script:Proc.HasExited) { break }
            Start-Sleep -Milliseconds 200
        }
        # The runner often dies ~1s AFTER the adapter appears ("netsh add ipv6 address failed" race),
        # so accept the attempt only if the sidecar is still alive and the adapter still present after
        # a short settle; otherwise restart it.
        if ($idx) {
            Start-Sleep -Milliseconds 1200
            if ((-not $script:Proc.HasExited) -and (Get-NetAdapter -Name $TunName -ErrorAction SilentlyContinue)) {
                $script:TunIdx = $idx
                break
            }
        }
        $reason = (Get-Content $errLog -ErrorAction SilentlyContinue | Where-Object { $_ -match 'error' } | Select-Object -Last 1)
        Write-Log "sidecar attempt $a failed ($reason)"
        if ($script:Proc -and -not $script:Proc.HasExited) {
            try { Stop-Process -Id $script:Proc.Id -Force -ErrorAction SilentlyContinue } catch {}
        }
        Start-Sleep -Milliseconds 1200  # let the half-created Wintun adapter tear down before retry
    }
    if (-not $script:TunIdx) { Fail "sidecar failed to start (adapter $TunName never came up) after $maxAttempts attempts" }
    Write-Log "$TunName is up (ifIndex $($script:TunIdx))"

    # 5a. Strip the tun's IPv6 entirely (global address + routes). The libXray runner gives the tun an
    # IPv6 ULA (fc00::1) and a ::/0 default, but the server (hysteria2, IPv4) and this network are
    # IPv4-only, so IPv6 black-holes. Removing just the ::/0 route is NOT enough: the fc00::1 ADDRESS
    # makes the OS believe IPv6 is usable, so browsers still try AAAA records (instagram.com, claude.ai
    # resolve to IPv6 here) over a dead IPv6 path and stall. Remove the global IPv6 address too so the
    # OS treats the tun as IPv4-only and uses A records. We do NOT disable the adapter's IPv6 binding:
    # the runner needs IPv6 enabled to assign fc00::1 at startup, and a disabled binding persists on the
    # reused Wintun device node and breaks every later connect. Leave link-local (fe80::) alone. Non-fatal.
    try {
        Get-NetRoute -InterfaceIndex $script:TunIdx -AddressFamily IPv6 -ErrorAction SilentlyContinue |
            Where-Object { $_.DestinationPrefix -notmatch '^fe80' } |
            Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue
        Get-NetIPAddress -InterfaceIndex $script:TunIdx -AddressFamily IPv6 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notmatch '^fe80' } |
            Remove-NetIPAddress -Confirm:$false -ErrorAction SilentlyContinue
        Write-Log "stripped tun global IPv6 (address + routes) - egress is IPv4-only"
    } catch {
        Write-Log "WARN: could not strip tun IPv6: $($_.Exception.Message)"
    }

    # 5b. Full-tunnel routing. The sidecar installs only a 0.0.0.0/0 on the tun, whose effective
    # metric merely ties the physical NIC and loses to other VPN adapters, so traffic does NOT
    # reliably egress through the tunnel. Add /1 split routes via the tun gateway: two /1s are strictly
    # more specific than any /0, so everything except the excluded server /32 goes through the tunnel
    # regardless of competing /0 metrics - matching the Android/iOS/macOS full-tunnel behaviour.
    $tunGw = $null
    for ($i = 0; $i -lt 25; $i++) {
        $tunGw = (Get-NetRoute -InterfaceIndex $script:TunIdx -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
            Select-Object -First 1).NextHop
        if ($tunGw) { break }
        Start-Sleep -Milliseconds 200
    }
    if ($tunGw) {
        foreach ($half in $SplitHalves) {
            try {
                New-NetRoute -DestinationPrefix $half -InterfaceIndex $script:TunIdx `
                    -NextHop $tunGw -RouteMetric 1 -ErrorAction Stop | Out-Null
            } catch {
                cmd /c "route add $($half.Split('/')[0]) mask 128.0.0.0 $tunGw metric 1 if $($script:TunIdx)" | Out-Null
            }
        }
        Write-Log "split-default routes via $tunGw (ifIndex $($script:TunIdx))"
    } else {
        Write-Log "WARN: tun gateway not found; full-tunnel split routes NOT added (traffic may bypass the tunnel)"
    }

    # 5c. DNS through the tunnel. A full-tunnel unblock VPN must resolve DNS via the tunnel to a clean
    # upstream, otherwise queries (a) leak to the local ISP and (b) hit ISP DNS poisoning, so blocked
    # sites stay blocked even though the tunnel could reach them. The libXray runner leaves the tun's
    # IPv4 DNS unset, so set it to the configured resolver here; with the tun preferred (lowest
    # interface metric) the OS sends DNS to it, and the /1 routes carry it to the server.
    $dns = '1.1.1.1'
    try { $rj = Get-Content $RunJson -Raw -ErrorAction Stop | ConvertFrom-Json; if ($rj.dns) { $dns = "$($rj.dns)" } } catch {}
    try {
        Set-DnsClientServerAddress -InterfaceIndex $script:TunIdx -ServerAddresses $dns -ErrorAction Stop
        Write-Log "tun DNS set to $dns"
    } catch {
        Write-Log "WARN: could not set tun DNS ($dns): $($_.Exception.Message)"
    }

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
