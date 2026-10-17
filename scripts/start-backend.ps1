$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Get-Content (Join-Path $projectRoot '.env') | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
if ($env:SPRING_PROFILES_ACTIVE -ne 'cloud') { throw 'SPRING_PROFILES_ACTIVE must be cloud.' }
foreach ($name in @('CLOUD_DB_HOST','CLOUD_DB_PORT','CLOUD_DB_NAME','CLOUD_DB_USERNAME','CLOUD_DB_PASSWORD','ADMIN_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) { throw "$name must be configured in .env." }
}
if ($env:CLOUD_DB_NAME -ne 'melonhub_rbac') { throw 'CLOUD_DB_NAME must be melonhub_rbac.' }
if ($env:ADMIN_PASSWORD.Length -lt 12) { throw 'ADMIN_PASSWORD must contain at least 12 characters.' }
$jarPath = Join-Path $projectRoot 'backend/target/rbac-service-1.0.0.jar'
if (-not (Test-Path $jarPath)) { throw 'Run mvn -f backend/pom.xml package first.' }
& java -jar $jarPath
