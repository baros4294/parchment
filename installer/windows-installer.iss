; Parchment — Minimal Zero-Trace Windows Installer (Inno Setup)
; Automatically compiled in GitHub Actions after ./gradlew createDistributable

#define MyAppName "Parchment"
#ifndef MyAppVersion
  #define MyAppVersion "2.0.0"
#endif
#define MyAppPublisher "Parchment"
#define MyAppExeName "Parchment.exe"

[Setup]
AppId={{8D49C66A-9870-4C97-9499-684F84A8E432}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\build\installer
OutputBaseFilename=Parchment-Windows-x64-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupIconFile=parchment-icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\composeApp\build\compose\binaries\main\app\Parchment\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

; ==============================================================================
; SAFE UNINSTALLATION: Only removes the application binaries in {app},
; NEVER deleting %LOCALAPPDATA%\ParchmentAI so updates/reinstalls keep all user data!
; ==============================================================================
[UninstallDelete]
Type: filesandordirs; Name: "{app}"
