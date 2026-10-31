#ifndef PublishDir
  #error PublishDir must point to the self-contained publish directory
#endif
#ifndef OutputDir
  #define OutputDir "..\dist"
#endif
[Setup]
AppId={{E21F5762-C409-4C59-9581-AAD989B88998}
AppName=流量看板
AppVersion=1.0.0
AppVerName=流量看板 1.0.0
AppPublisher=Max1426
AppPublisherURL=https://github.com/Max1426/traffic-board
DefaultDirName={localappdata}\Programs\TrafficBoard
DefaultGroupName=流量看板
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19045
OutputDir={#OutputDir}
OutputBaseFilename=TrafficBoard-Windows-Setup-1.0.0-x64
Compression=lzma2/normal
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\TrafficBoard.exe
CloseApplications=yes
RestartApplications=no
SetupLogging=yes
SetupIconFile=..\src\TrafficBoard.App\Assets\traffic-board.ico
LicenseFile=..\..\LICENSE

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加选项："; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "..\THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\流量看板"; Filename: "{app}\TrafficBoard.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\流量看板"; Filename: "{app}\TrafficBoard.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\TrafficBoard.exe"; Description: "启动流量看板"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\TrafficBoard.exe"; Parameters: "--unregister"; Flags: runhidden; RunOnceId: "NotificationUnregister"

[Code]
function InitializeUninstall(): Boolean;
begin
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\TrafficBoard');
    if (not UninstallSilent) and DirExists(DataDir) then
      if MsgBox('是否同时删除本机订阅、统计与设置？' + #13#10 + '选择“否”将保留数据，重新安装后可以继续使用。', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
    RegDeleteKeyIncludingSubkeys(HKCU, 'Software\TrafficBoard');
  end;
end;
