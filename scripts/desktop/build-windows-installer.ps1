<#
  Builds the OnthecrowVPN Windows installer (.exe).

  Two stages:
    1) Compose Desktop / jpackage -> a self-contained app-image (bundled JRE + VPN engine + Firebase config).
    2) Inno Setup (ISCC) -> a normal installer .exe with Start-menu / desktop shortcuts and an uninstaller.

  Why not jpackage's own MSI? That needs WiX 3 + .NET Framework 3.5, which isn't always installable
  (e.g. NetFx3 payload removed + Windows Update unavailable). Inno Setup has no such dependency.

  Prereqs (installed once):
    - A full JDK 21 WITH jpackage (the Android Studio JBR does NOT include it).
        winget install EclipseAdoptium.Temurin.21.JDK
    - Inno Setup 6:
        winget install JRSoftware.InnoSetup
    - local-libs/libxray-desktop/windows-x64/ with onthecrow-xray.exe, onthecrow-convert.exe, wintun.dll
    - desktopApp/firebase-admin.properties (bundled into the app so installed copies can load configs)

  Run from the repo root:  powershell -ExecutionPolicy Bypass -File scripts\desktop\build-windows-installer.ps1
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
Set-Location $repo

# 1. Find a JDK that has jpackage (search common locations + JAVA_HOME).
function Find-Jpackage {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\jpackage.exe') }
    $candidates += (Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium\jdk-21*\bin\jpackage.exe",
                                  "$env:ProgramFiles\Java\jdk-21*\bin\jpackage.exe",
                                  "$env:ProgramFiles\Microsoft\jdk-21*\bin\jpackage.exe" -ErrorAction SilentlyContinue | ForEach-Object FullName)
    $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
$jpackage = Find-Jpackage
if (-not $jpackage) { throw "No JDK with jpackage found. Install one: winget install EclipseAdoptium.Temurin.21.JDK" }
$jdkHome = Split-Path (Split-Path $jpackage)
Write-Host "Using JDK (jpackage): $jdkHome"

# 2. Locate ISCC (Inno Setup).
$iscc = Get-ChildItem "${env:ProgramFiles(x86)}\Inno Setup*\ISCC.exe","$env:ProgramFiles\Inno Setup*\ISCC.exe","$env:LOCALAPPDATA\Programs\Inno Setup*\ISCC.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $iscc) { throw "ISCC.exe (Inno Setup) not found. Install: winget install JRSoftware.InnoSetup" }
Write-Host "Using Inno Setup: $($iscc.FullName)"

# 3. Build the app-image (jpackage) with the full JDK.
Write-Host "`n==> Building app-image (gradlew :desktopApp:createDistributable)..."
$env:JAVA_HOME = $jdkHome
& "$repo\gradlew.bat" :desktopApp:createDistributable --console=plain
if ($LASTEXITCODE -ne 0) { throw "createDistributable failed ($LASTEXITCODE)" }

$appDir = Join-Path $repo 'desktopApp\build\compose\binaries\main\app\OnthecrowVPN'
if (-not (Test-Path (Join-Path $appDir 'OnthecrowVPN.exe'))) { throw "app-image not found at $appDir" }
$outDir = Join-Path $repo 'desktopApp\build\installer'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 4. Package the installer (Inno Setup).
Write-Host "`n==> Building installer (ISCC)..."
& $iscc.FullName "/DAppDir=$appDir" "/DOutDir=$outDir" "$repo\scripts\desktop\windows-installer.iss"
if ($LASTEXITCODE -ne 0) { throw "ISCC failed ($LASTEXITCODE)" }

$setup = Get-ChildItem (Join-Path $outDir '*-setup.exe') | Sort-Object LastWriteTime | Select-Object -Last 1
Write-Host "`nDONE. Installer: $($setup.FullName)  ($([math]::Round($setup.Length/1MB,1)) MB)"
