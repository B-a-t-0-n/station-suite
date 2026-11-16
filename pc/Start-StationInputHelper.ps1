param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Target,
    [string]$Apk = (Join-Path $PSScriptRoot '..\dist\station-agent-0.7.2.apk')
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
if ($Target -notmatch '^[A-Za-z0-9_.:-]{1,128}$') { throw 'Invalid ADB target' }
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkHash -notmatch '^[a-f0-9]{64}$') { throw 'Invalid APK hash' }
$remoteApk = "/data/local/tmp/station-input/agent-$apkHash.apk"
function Invoke-StationAdb([string[]]$Arguments) {
    # Windows PowerShell 5 treats even successful adb push progress on stderr
    # as an error record. Use the native exit code to decide whether it failed.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $adbPath -s $Target @Arguments 2>&1
        $adbExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($adbExitCode -ne 0) { throw 'Station input helper ADB step failed' }
    return ($output -join "`n")
}
Invoke-StationAdb -Arguments @('shell', 'mkdir', '-p', '/data/local/tmp/station-input') | Out-Null
Invoke-StationAdb -Arguments @('shell', 'chmod', '700', '/data/local/tmp/station-input') | Out-Null
$null = & $adbPath -s $Target shell test -r $remoteApk 2>&1
if ($LASTEXITCODE -ne 0) { Invoke-StationAdb -Arguments @('push', $apkPath, $remoteApk) | Out-Null }
Invoke-StationAdb -Arguments @('shell', 'chmod', '600', $remoteApk) | Out-Null
$pingCommand = "CLASSPATH=$remoteApk /system/bin/app_process /system/bin local.station.agent.StationInputHelper --ping"
function Read-StationInputReady {
    try {
        $statusText = Invoke-StationAdb -Arguments @('shell', $pingCommand)
        $state = $statusText | ConvertFrom-Json
        return $state.ok -eq $true -and $state.ready -eq $true -and $state.scope -eq 'station_input' -and $state.protocol -eq 1
    } catch { return $false }
}
if (-not (Read-StationInputReady)) {
    $nohup = Invoke-StationAdb -Arguments @('shell', 'command -v nohup')
    if ([string]::IsNullOrWhiteSpace($nohup)) { throw 'Android nohup unavailable; helper not started' }
    $launch = "CLASSPATH=$remoteApk nohup /system/bin/app_process /system/bin local.station.agent.StationInputHelper --daemon </dev/null >/dev/null 2>&1 &"
    Invoke-StationAdb -Arguments @('shell', $launch) | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Read-StationInputReady)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Station input helper did not become ready' }
        Start-Sleep -Milliseconds 100
    }
}
Write-Output 'Station input helper ready; PING only, no key pressed.'
