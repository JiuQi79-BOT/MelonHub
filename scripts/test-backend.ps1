$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
$values = Read-MelonConfiguration
foreach ($name in $values.Keys) {
    # Set-Item/Remove-Item instead of SetEnvironmentVariable: an empty value must not create an
    # empty variable that overrides a Spring default.
    if ($values[$name]) { Set-Item -LiteralPath ("Env:" + $name) -Value $values[$name] }
    else { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
}
$env:SEED_DEMO = 'false'
$env:SPRING_PROFILES_ACTIVE = 'test'
if ($env:TEST_DB_URL -notmatch '^jdbc:mysql://[^/]+/melonhub_rbac_test(?:\?|$)') {
    throw 'TEST_DB_URL must point to the dedicated melonhub_rbac_test database.'
}
if (-not $env:TEST_DB_USERNAME -or -not $env:TEST_DB_PASSWORD) {
    throw 'Configure TEST_DB_USERNAME and TEST_DB_PASSWORD in .env.'
}
Start-MelonRedis
$java = Get-MelonJava
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
& (Get-MelonMaven) -f (Join-Path $projectRoot 'backend/pom.xml') test -B -ntp
exit $LASTEXITCODE
