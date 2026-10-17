$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Get-Content (Join-Path $projectRoot '.env') | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
$env:SEED_DEMO = 'false'
$env:SPRING_PROFILES_ACTIVE = 'test'
if ($env:TEST_DB_URL -notmatch '^jdbc:mysql://[^/]+/melonhub_rbac_test(?:\?|$)') {
    throw 'TEST_DB_URL must point to the dedicated melonhub_rbac_test database.'
}
if (-not $env:TEST_DB_USERNAME -or -not $env:TEST_DB_PASSWORD) {
    throw 'Configure TEST_DB_USERNAME and TEST_DB_PASSWORD in .env.'
}
& mvn -f (Join-Path $projectRoot 'backend/pom.xml') test
exit $LASTEXITCODE
