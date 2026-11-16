param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$AgentApk,
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256,
    [switch]$Status
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$apkPath = (Resolve-Path -LiteralPath $AgentApk).Path
if ($Target -notmatch '^[A-Za-z0-9_.:-]{1,128}$') { throw 'Invalid ADB target' }
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($ExpectedSha256 -and $apkHash -ne $ExpectedSha256.ToLowerInvariant()) { throw 'Agent APK hash mismatch' }
function Invoke-PairingAdb([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $adbPath -s $Target @Arguments 2>&1
        $adbExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($adbExitCode -ne 0) { throw 'Station Bluetooth pairing ADB step failed' }
    return ($output -join "`n")
}
if ((Invoke-PairingAdb -Arguments @('get-state')).Trim() -ne 'device') { throw 'Station ADB is not ready' }
$remoteDirectory = '/data/local/tmp/station-bluetooth-pairing'
$remoteApk = "$remoteDirectory/agent-$apkHash.apk"
Invoke-PairingAdb -Arguments @('shell', 'mkdir', '-p', $remoteDirectory) | Out-Null
Invoke-PairingAdb -Arguments @('shell', 'chmod', '700', $remoteDirectory) | Out-Null
$null = & $adbPath -s $Target shell test -r $remoteApk 2>&1
if ($LASTEXITCODE -ne 0) { Invoke-PairingAdb -Arguments @('push', $apkPath, $remoteApk) | Out-Null }
Invoke-PairingAdb -Arguments @('shell', 'chmod', '600', $remoteApk) | Out-Null
$remoteHash = (Invoke-PairingAdb -Arguments @('shell', 'sha256sum', $remoteApk)).Split(' ')[0].Trim()
if ($remoteHash -cne $apkHash) { throw 'Remote Agent APK hash mismatch' }
$statusCommand = "CLASSPATH=$remoteApk /system/bin/app_process /system/bin local.station.agent.StationBluetoothPairing --status"
if ($Status) {
    $value = (Invoke-PairingAdb -Arguments @('shell', $statusCommand)) | ConvertFrom-Json
} else {
    $nohup = Invoke-PairingAdb -Arguments @('shell', 'command -v nohup')
    if ([string]::IsNullOrWhiteSpace($nohup)) { throw 'Android nohup unavailable; pairing window not opened' }
    $statusFile = "$remoteDirectory/window-$apkHash.json"
    Invoke-PairingAdb -Arguments @('shell', 'rm', '-f', $statusFile) | Out-Null
    $launch = "CLASSPATH=$remoteApk nohup /system/bin/app_process /system/bin local.station.agent.StationBluetoothPairing --open120 </dev/null >$statusFile 2>/dev/null &"
    Invoke-PairingAdb -Arguments @('shell', $launch) | Out-Null
    $value = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        try { $value = (Invoke-PairingAdb -Arguments @('shell', 'cat', $statusFile)) | ConvertFrom-Json }
        catch { $value = $null }
        if ($null -eq $value) { Start-Sleep -Milliseconds 100 }
    } while ($null -eq $value -and [DateTime]::UtcNow -lt $deadline)
    if ($null -eq $value) { throw 'Station Bluetooth pairing window did not report readiness' }
}
if ($value.ok -ne $true -or $value.scope -ne 'station_bluetooth_pairing' -or $value.protocol -ne 1) {
    throw 'Station Bluetooth pairing helper is unavailable'
}
if (-not $Status -and ($value.mode -ne 'discoverable' -or $value.windowSeconds -ne 120)) {
    throw 'Station Bluetooth pairing window did not open'
}
$value | ConvertTo-Json -Compress
