[Setup]
AppName=Photobooth Server
AppVersion=3.1
DefaultDirName={autopf}\Photobooth
DefaultGroupName=Photobooth
PrivilegesRequired=admin
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
SetupLogging=yes
OutputBaseFilename=PhotoboothSetup
OutputDir=..\dist

[Dirs]
Name: "{commonappdata}\Photobooth"; Permissions: users-modify

[Files]
Source: "..\dist\app\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion
Source: "..\dist\admin\*"; DestDir: "{app}\admin"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Photobooth\Photobooth Admin"; Filename: "{app}\admin\PhotoboothAdmin.exe"
Name: "{commonstartup}\Photobooth Admin"; Filename: "{app}\admin\PhotoboothAdmin.exe"; Parameters: "--minimized"

[Run]
Filename: "{app}\admin\PhotoboothAdmin.exe"; Description: "Open Photobooth Admin"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM Photobooth.exe"; Flags: runhidden; RunOnceId: "killserver"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM PhotoboothAdmin.exe"; Flags: runhidden; RunOnceId: "killadmin"
Filename: "{sys}\sc.exe"; Parameters: "stop PhotoboothServer"; Flags: runhidden; RunOnceId: "stop"
Filename: "{sys}\sc.exe"; Parameters: "delete PhotoboothServer"; Flags: runhidden; RunOnceId: "del"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Photobooth"""; Flags: runhidden; RunOnceId: "fw"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Photobooth discovery"""; Flags: runhidden; RunOnceId: "fw2"

[Code]
var CfPage: TInputQueryWizardPage;

function JsonEsc(S: String): String;
begin
  Result := S;
  StringChangeEx(Result, '\', '\\', True);
  StringChangeEx(Result, '"', '\"', True);
end;

// Runs a helper program. A failure is written to the setup log instead of showing an error box.
procedure Run(F, P: String; Wait: Boolean);
var RC: Integer; M: TExecWait;
begin
  if Wait then M := ewWaitUntilTerminated else M := ewNoWait;
  if Exec(F, P, '', SW_HIDE, M, RC) then
    Log('OK ' + F + ' ' + P + ' (code ' + IntToStr(RC) + ')')
  else
    Log('FAILED to start ' + F + ' ' + P + ' (code ' + IntToStr(RC) + ': ' + SysErrorMessage(RC) + ')');
end;

procedure InitializeWizard;
begin
  CfPage := CreateInputQueryPage(wpSelectDir, 'Cloudflare and admin setup', 'Connect this server to your Cloudflare tunnel',
    'In Cloudflare Zero Trust, create a tunnel and add a public hostname that points to http://localhost:5080. Paste the tunnel token and that hostname below. Leave everything empty to set it up later, or on an upgrade to keep the current settings.');
  CfPage.Add('Tunnel token:', False);
  CfPage.Add('Public hostname (for example photos.example.com):', False);
  CfPage.Add('Admin password (at least 8 characters):', True);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = CfPage.ID then
  begin
    if (CfPage.Values[2] <> '') and (Length(CfPage.Values[2]) < 8) then
    begin
      MsgBox('The admin password must be at least 8 characters.', mbError, MB_OK);
      Result := False;
    end
    else if (CfPage.Values[0] <> '') and (CfPage.Values[1] = '') then
    begin
      MsgBox('Enter the public hostname that points to your tunnel.', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Run(ExpandConstant('{sys}\sc.exe'), 'stop PhotoboothServer', True);
  Run(ExpandConstant('{sys}\taskkill.exe'), '/F /IM PhotoboothAdmin.exe', True);
  Run(ExpandConstant('{sys}\taskkill.exe'), '/F /IM Photobooth.exe', True);
  Sleep(3000);
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
var Dir, Json: String;
begin
  if CurStep = ssPostInstall then
  begin
    Log('ssPostInstall: begin');
    Dir := ExpandConstant('{commonappdata}\Photobooth');
    ForceDirectories(Dir);
    Log('ssPostInstall: data folder ready');
    if (CfPage.Values[0] <> '') or (CfPage.Values[1] <> '') or (CfPage.Values[2] <> '') then
    begin
      Json := '{"tunnelToken":"' + JsonEsc(Trim(CfPage.Values[0])) + '","publicHostname":"' + JsonEsc(Trim(CfPage.Values[1])) + '","adminPassword":"' + JsonEsc(CfPage.Values[2]) + '"}';
      SaveStringToFile(Dir + '\install.json', Json, False);
      Log('ssPostInstall: install.json written');
    end;
    // No Windows service: Photobooth Admin (started at login) starts the server in the background.
    Log('ssPostInstall: done');
  end;
end;
