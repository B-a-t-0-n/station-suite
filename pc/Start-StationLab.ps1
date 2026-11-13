[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$DeploymentFile)
$ErrorActionPreference = 'Stop'
$DeploymentFile = (Resolve-Path -LiteralPath $DeploymentFile).Path
$settings = Get-Content -LiteralPath $DeploymentFile -Raw | ConvertFrom-Json
& $settings.python -B (Join-Path $PSScriptRoot 'start.py') --deployment $DeploymentFile
exit $LASTEXITCODE
