# One scheduler-friendly keepalive invocation. No NIC, task, DHCP, or foreign process changes.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][Alias('DeploymentFile')][string]$Deployment,
    [switch]$ExternalDhcp
)
$ErrorActionPreference = 'Stop'
$deploymentPath = (Resolve-Path -LiteralPath $Deployment).Path
$settings = Get-Content -LiteralPath $deploymentPath -Raw | ConvertFrom-Json
$arguments = @('-B', (Join-Path $PSScriptRoot 'start.py'), '--deployment', $deploymentPath, '--ensure')
if ($ExternalDhcp) { $arguments += '--external-dhcp' }
& $settings.python @arguments
exit $LASTEXITCODE
