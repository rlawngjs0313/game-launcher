#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef PackageDir
  #error PackageDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

[Setup]
AppId={{6D1C343D-B553-4591-AFD4-8AC2656C0F22}
AppName=Game Launcher
AppVersion={#AppVersion}
AppVerName=Game Launcher {#AppVersion}
AppPublisher=rlawngjs0313
AppPublisherURL=https://github.com/rlawngjs0313/game-launcher
DefaultDirName={localappdata}\Programs\GameLauncher
DefaultGroupName=Game Launcher
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDir}
OutputBaseFilename=GameLauncher-{#AppVersion}-windows-x64-setup
Compression=lzma2/normal
SolidCompression=yes
WizardStyle=modern
DisableStartupPrompt=yes
DisableWelcomePage=yes
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
DisableFinishedPage=yes
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
UninstallDisplayIcon={app}\GameLauncher.exe

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#PackageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Game Launcher"; Filename: "{app}\GameLauncher.exe"; WorkingDir: "{app}"

[Run]
Filename: "{app}\GameLauncher.exe"; WorkingDir: "{app}"; Flags: nowait skipifsilent

[Code]
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  { Inno otherwise keeps the Ready page if every earlier page is disabled. }
  Result := PageID = wpReady;
end;
