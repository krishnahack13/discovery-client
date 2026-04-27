[Setup]
AppName=Vistora Agent
AppVersion={#AppVersion}
AppPublisher=Vistora AI Technologies
DefaultDirName={autopf}\VistoraAgent
DefaultGroupName=Vistora Agent
OutputBaseFilename=VistoraAgent-Setup-{#AppVersion}
OutputDir=dist
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
PrivilegesRequired=lowest
DisableProgramGroupPage=yes

[Files]
; 1. Copy the standalone Java Agent compiled by jpackage
Source: "dist\VistoraAgent\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; 2. Bundle the unpacked browser extension cleanly into the install folder
Source: "extension\*"; DestDir: "{app}\browser-extension"; Flags: ignoreversion recursesubdirs createallsubdirs
; 3. Include the setup instructions for Developer Mode
Source: "Extension_Instructions.html"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Vistora Agent"; Filename: "{app}\VistoraAgent.exe"
Name: "{autodesktop}\Vistora Agent"; Filename: "{app}\VistoraAgent.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Run]
; Auto-launch the Java Agent silently in the background
Filename: "{app}\VistoraAgent.exe"; Description: "Launch Vistora Agent"; Flags: nowait postinstall skipifsilent
; Post-install: Auto-open the HTML instructions in the local default browser to force Extension setup
Filename: "{app}\Extension_Instructions.html"; Description: "Open Browser Extension Setup Guide"; Flags: shellexec nowait postinstall skipifsilent
