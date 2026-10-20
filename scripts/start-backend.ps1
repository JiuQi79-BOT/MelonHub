$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
Set-Location -LiteralPath $script:MelonRoot
Import-MelonRuntimeConfiguration (Read-MelonConfiguration)
$jarPath = Join-Path $script:MelonRoot 'backend/target/rbac-service-1.0.0.jar'
if (-not (Test-Path $jarPath)) { throw 'Run mvn -f backend/pom.xml package first.' }
& (Join-Path $PSScriptRoot 'run-service.ps1') -Service backend -Port 8080
