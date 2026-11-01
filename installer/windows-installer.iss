; Parchment — Minimal Zero-Trace Windows Installer (Inno Setup)
; Automatically compiled in GitHub Actions after ./gradlew createDistributable

#define MyAppName "Parchment"
#define MyAppVersion "1.0.0"
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
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
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
; ZERO-TRACE UNINSTALLATION: Removes 100% of binaries, runtime, user settings,
; caches, and appdata directories so zero trace of the app remains on Windows.
; ==============================================================================
[UninstallDelete]
Type: filesandordirs; Name: "{app}"
Type: filesandordirs; Name: "{localappdata}\ParchmentAI"
Type: filesandordirs; Name: "{userappdata}\ParchmentAI"
Type: filesandordirs; Name: "{%USERPROFILE}\.parchment-ai"

[Code]
procedure CurStepChanged(CurStep: TUninstallStep);
begin
  if CurStep = usPostUninstall then
  begin
    DelTree(ExpandConstant('{localappdata}\ParchmentAI'), True, True, True);
    DelTree(ExpandConstant('{userappdata}\ParchmentAI'), True, True, True);
    DelTree(ExpandConstant('{app}'), True, True, True);
  end;
end;
