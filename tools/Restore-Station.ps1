#requires -Version 5.1
<# Restore a prepared private profile, then ensure the existing PC transport. #>
[CmdletBinding()]
param(
    [string]$Profile = (Join-Path $env:USERPROFILE '.station-suite\recovery\recovery.json'),
    [string]$Deployment = (Join-Path $env:USERPROFILE '.station-suite\deployment.json'),
    [switch]$Apply,
    [switch]$ExternalDhcp,
    [switch]$PairBluetooth
)
$ErrorActionPreference = 'Stop'
$suite = Split-Path -Parent $PSScriptRoot
$Profile = (Resolve-Path -LiteralPath $Profile).Path
$Deployment = (Resolve-Path -LiteralPath $Deployment).Path
$settings = Get-Content -LiteralPath $Deployment -Raw | ConvertFrom-Json
$profileSettings = Get-Content -LiteralPath $Profile -Raw | ConvertFrom-Json
$python = (Resolve-Path -LiteralPath $settings.python).Path
if ($profileSettings.serial -cne $settings.serial -or
    (Resolve-Path -LiteralPath $profileSettings.adb).Path -ne (Resolve-Path -LiteralPath $settings.adb).Path) {
    throw 'Recovery profile and PC deployment must select the same ADB tool and Station.'
}

function Invoke-RecoveryPython([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $python @Arguments
        $resultCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($resultCode -ne 0) { throw 'Station recovery stopped. See the diagnostic code above.' }
}

function Get-RecoveryTransportStatus {
    $arguments = @('-B', (Join-Path $suite 'pc\start.py'), '--deployment', $Deployment, '--status')
    if ($ExternalDhcp) { $arguments += '--external-dhcp' }
    $output = @(Invoke-RecoveryPython -Arguments $arguments)
    $value = ($output -join "`n") | ConvertFrom-Json
    if ($value.ok -ne $true -or $value.running -isnot [bool]) {
        throw 'PC transport status could not be verified.'
    }
    return $value
}

function Assert-RecoveryTransportIdle {
    $arguments = @('-B', (Join-Path $PSScriptRoot 'check_recovery_transport.py'), '--deployment', $Deployment)
    if ($ExternalDhcp) { $arguments += '--external-dhcp' }
    Invoke-RecoveryPython -Arguments $arguments
}

# Check both complete sets of local inputs before any installation or service start.
Invoke-RecoveryPython -Arguments @('-B', (Join-Path $suite 'pc\start.py'), '--deployment', $Deployment, '--check')
Invoke-RecoveryPython -Arguments @('-B', (Join-Path $PSScriptRoot 'recover.py'), '--profile', $Profile)
if (-not $Apply) {
    Write-Output 'Preflight passed. Add -Apply to restore this prepared profile.'
    exit 0
}

# Validate the optional pairing input before provisioning anything.
if ($PairBluetooth) {
    $agent = @($profileSettings.apps | Where-Object { $_.role -eq 'agent' })
    if ($agent.Count -ne 1 -or -not $agent[0].sha256 -or
        (Get-FileHash -LiteralPath $settings.agentApk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $agent[0].sha256) {
        throw 'PC deployment must use the Agent APK pinned in the recovery profile.'
    }
}

$stateDirectory = Join-Path $settings.privateDir 'state'
$stopPath = Join-Path $stateDirectory 'stop'
if (Test-Path -LiteralPath $stopPath) {
    throw 'An explicit PC stop marker already exists. Resolve it before starting recovery.'
}
$previousOwner = Get-RecoveryTransportStatus
if (-not $previousOwner.running) { Assert-RecoveryTransportIdle }
[System.IO.Directory]::CreateDirectory($stateDirectory) | Out-Null
$stopToken = 'station-recovery:' + [guid]::NewGuid().ToString('N')
# CreateNew never adopts or removes a stop marker left by another operation.
$stopHandle = [System.IO.File]::Open($stopPath, [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
try {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($stopToken)
    $stopHandle.Write($bytes, 0, $bytes.Length)
    $stopHandle.Flush()
    $deadline = [System.Diagnostics.Stopwatch]::StartNew()
    $ownerPid = if ($previousOwner.running) { $previousOwner.ownerPid } else { $null }
    while ($true) {
        $currentOwner = Get-RecoveryTransportStatus
        if (-not $currentOwner.running) { break }
        if ($null -ne $ownerPid -and $currentOwner.ownerPid -ne $ownerPid) {
            throw 'PC transport owner changed during recovery shutdown.'
        }
        $ownerPid = $currentOwner.ownerPid
        if ($deadline.Elapsed.TotalSeconds -ge 30) {
            throw 'PC transport did not stop within 30 seconds; no process was killed.'
        }
        Start-Sleep -Milliseconds 200
    }
    Assert-RecoveryTransportIdle
    Invoke-RecoveryPython -Arguments @('-B', (Join-Path $PSScriptRoot 'recover.py'), '--profile', $Profile, '--apply')
} catch {
    Write-Warning "Recovery stopped; PC restart remains blocked by ${stopPath}. Diagnose the failure before explicitly removing that marker."
    throw
} finally {
    $stopHandle.Dispose()
}
if ([System.IO.File]::ReadAllText($stopPath) -cne $stopToken) {
    throw 'Recovery stop marker changed; PC transport remains stopped.'
}
Remove-Item -LiteralPath $stopPath
$transportArguments = @('-B', (Join-Path $suite 'pc\start.py'), '--deployment', $Deployment, '--ensure')
if ($ExternalDhcp) { $transportArguments += '--external-dhcp' }
Invoke-RecoveryPython -Arguments $transportArguments
if ($PairBluetooth) {
    & (Join-Path $suite 'pc\Pair-StationBluetooth.ps1') -Adb $settings.adb -Target $settings.serial `
        -AgentApk $settings.agentApk -ExpectedSha256 $agent[0].sha256
    if (-not $?) { throw 'Bluetooth pairing window was not opened.' }
}
Write-Output 'Profile restored; PC transport startup requested. Bluetooth pairing and service sign-ins remain personal.'
