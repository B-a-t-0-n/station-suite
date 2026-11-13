# Run elevated after private restore and explicit dedicated Ethernet selection.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$DeploymentFile, [switch]$AtLogon, [switch]$Start)
$ErrorActionPreference = 'Stop'
$DeploymentFile = (Resolve-Path -LiteralPath $DeploymentFile).Path
$settings = Get-Content -LiteralPath $DeploymentFile -Raw | ConvertFrom-Json
& $settings.python -B (Join-Path $PSScriptRoot 'start.py') --deployment $DeploymentFile --check
if ($LASTEXITCODE -ne 0) { throw 'Private deployment validation failed.' }
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Run this installation from an elevated PowerShell.' }
$labNic = Get-NetAdapter -InterfaceIndex $settings.labInterfaceIndex -ErrorAction Stop
$wanNic = Get-NetAdapter -InterfaceIndex $settings.wanInterfaceIndex -ErrorAction Stop
if ([guid]$labNic.InterfaceGuid -ne [guid]$settings.labInterfaceGuid) { throw 'Dedicated adapter identity differs.' }
if ($labNic.InterfaceIndex -eq $wanNic.InterfaceIndex -or -not $labNic.HardwareInterface -or -not $wanNic.HardwareInterface) { throw 'Select two different physical interfaces.' }
if (Get-NetRoute -InterfaceIndex $labNic.InterfaceIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue) { throw 'Dedicated adapter already has a default gateway.' }
$currentIps = @(Get-NetIPAddress -InterfaceIndex $labNic.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue)
if (@($currentIps | Where-Object { $_.IPAddress -notin @($settings.labIp, $settings.voiceIp) -and $_.IPAddress -notlike '169.254.*' }).Count) { throw 'Dedicated adapter has unrelated addresses; no changes applied.' }
# ICS and bridge membership require manual resolution; never turn a home LAN into this lab.
$sharing = New-Object -ComObject HNetCfg.HNetShare
foreach ($connection in $sharing.EnumEveryConnection()) {
    $properties = $sharing.NetConnectionProps($connection)
    if ([guid]$properties.Guid -eq [guid]$labNic.InterfaceGuid -and $sharing.INetSharingConfigurationForINetConnection($connection).SharingEnabled) { throw 'Disable Internet Connection Sharing on the dedicated adapter first.' }
}
if ($labNic.InterfaceDescription -match 'Bridge|Virtual|Hyper-V|TAP|VPN') { throw 'Dedicated lab adapter cannot be a bridge or VPN.' }
$snapshot = Join-Path $settings.privateDir 'network-before-install.json'
if (-not (Test-Path -LiteralPath $snapshot)) {
    @{ InterfaceGuid=$labNic.InterfaceGuid; InterfaceIndex=$labNic.InterfaceIndex; Addresses=$currentIps; Settings=@(Get-NetIPInterface -InterfaceIndex $labNic.InterfaceIndex) } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $snapshot -Encoding UTF8
}
Set-NetIPInterface -InterfaceIndex $labNic.InterfaceIndex -AddressFamily IPv4 -Dhcp Disabled -Forwarding Disabled
Set-NetIPInterface -InterfaceIndex $labNic.InterfaceIndex -AddressFamily IPv6 -Forwarding Disabled -Advertising Disabled
foreach ($address in @($settings.labIp, $settings.voiceIp)) {
    if (-not @($currentIps | Where-Object IPAddress -eq $address).Count) { New-NetIPAddress -InterfaceIndex $labNic.InterfaceIndex -IPAddress $address -PrefixLength 24 | Out-Null }
}
$rules = @(
    @{ Name='DHCP'; Protocol='UDP'; LocalPort=67; RemotePort=68; Program=$settings.dhcpExecutable },
    @{ Name='DNS-UDP'; Protocol='UDP'; LocalPort=53; RemoteAddress=$settings.stationIp; Program=$settings.python },
    @{ Name='DNS-TCP'; Protocol='TCP'; LocalPort=53; RemoteAddress=$settings.stationIp; Program=$settings.python },
    @{ Name='CONFIG'; Protocol='TCP'; LocalPort=@(80,443); RemoteAddress=$settings.stationIp; Program=$settings.python },
    @{ Name='NTP'; Protocol='UDP'; LocalPort=123; RemoteAddress=$settings.stationIp; Program=$settings.python }
)
foreach ($rule in $rules) {
    $name = 'StationSuite-' + $rule.Name
    Remove-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
    $parameters = @{ Name=$name; DisplayName=$name; Group='StationSuite'; Direction='Inbound'; Action='Allow'; InterfaceAlias=$labNic.Name; Profile='Any'; Protocol=$rule.Protocol; LocalPort=$rule.LocalPort; Program=$rule.Program }
    if ($rule.RemoteAddress) { $parameters.RemoteAddress=$rule.RemoteAddress }
    if ($rule.RemotePort) { $parameters.RemotePort=$rule.RemotePort }
    New-NetFirewallRule @parameters | Out-Null
}
Remove-NetFirewallRule -Name 'StationSuite-DHCP-Management' -ErrorAction SilentlyContinue
New-NetFirewallRule -Name 'StationSuite-DHCP-Management' -DisplayName 'StationSuite-DHCP-Management' -Group 'StationSuite' -Direction Inbound -Action Block -Protocol TCP -LocalPort 2994 -Program $settings.dhcpExecutable -Profile Any | Out-Null
if ($AtLogon) {
    $launcher = Join-Path $PSScriptRoot 'Start-StationLab.ps1'
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $launcher + '" -DeploymentFile "' + $DeploymentFile + '"'
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
    $options = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Days 3650)
    Register-ScheduledTask -TaskName 'StationSuite-Tunnel' -Action $action -Trigger $trigger -Settings $options -RunLevel Highest -Force | Out-Null
}
Write-Host 'Dedicated network installed. Windows NAT/ICS was not enabled.'
if ($Start) { & (Join-Path $PSScriptRoot 'Start-StationLab.ps1') -DeploymentFile $DeploymentFile }
