; Inno Setup script for OnthecrowVPN (Windows).
; Wraps the Compose Desktop jpackage app-image into a normal installer (.exe) with Start-menu /
; desktop shortcuts and an uninstaller. Used because jpackage's own MSI path needs WiX 3 + .NET 3.5,
; which isn't always available; Inno Setup has no such dependency.
;
; Build:
;   1) .\gradlew :desktopApp:createDistributable      (produces the app-image, JAVA_HOME = a JDK with jpackage)
;   2) ISCC.exe /DAppDir="<...>\build\compose\binaries\main\app\OnthecrowVPN" \
;               /DOutDir="<...>\build\installer" scripts\desktop\windows-installer.iss
;
; AppDir / OutDir can be overridden on the ISCC command line; the defaults below assume the repo layout.

#ifndef AppDir
  #define AppDir "..\..\desktopApp\build\compose\binaries\main\app\OnthecrowVPN"
#endif
#ifndef OutDir
  #define OutDir "..\..\desktopApp\build\installer"
#endif
#define MyAppName "OnthecrowVPN"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Onthecrow"
#define MyAppExe "OnthecrowVPN.exe"

[Setup]
AppId={{B7E4C2A1-7F3D-4E6B-9C2A-3D1F8E5A4B60}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
UninstallDisplayIcon={app}\{#MyAppExe}
OutputDir={#OutDir}
OutputBaseFilename={#MyAppName}-{#MyAppVersion}-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; App-image bundles a 64-bit JRE, so install per-machine in 64-bit Program Files.
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExe}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
