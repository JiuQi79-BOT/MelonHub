param([ValidateSet('status','start','stop','restart')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
& (Get-MelonPython) (Join-Path $script:MelonRoot 'scripts/server-backend.py') $Action
exit $LASTEXITCODE
