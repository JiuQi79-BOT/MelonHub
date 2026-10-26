param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
if (-not $SkipBuild) {
    $java = Get-MelonJava
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    & (Get-MelonMaven) -f (Join-Path $script:MelonRoot 'backend/pom.xml') package -DskipTests -B -ntp
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed; server was not changed.' }
}
& (Get-MelonPython) (Join-Path $script:MelonRoot 'scripts/server-backend.py') deploy
if ($LASTEXITCODE -ne 0) { throw 'Server deployment failed. See the deployment guide.' }
