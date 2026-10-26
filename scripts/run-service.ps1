param([Parameter(Mandatory)][ValidateSet('backend','frontend')][string]$Service, [int]$Port = 5174)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
$configuration = Read-MelonConfiguration
$logDirectory = Join-Path $script:MelonRoot '.local/logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$logFile = Join-Path $logDirectory ($Service + '.log')
$redactions = @($configuration.Keys | Where-Object { $_ -match 'PASSWORD|_HOST$' } | ForEach-Object { $configuration[$_] } | Where-Object { $_ } | Sort-Object { $_.Length } -Descending)
function Write-ServiceLog($Value) {
    $line = [string]$Value
    foreach ($secret in $redactions) { $line = $line.Replace($secret, '[redacted]') }
    Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8
}
try {
    Set-Location -LiteralPath $script:MelonRoot
    Write-ServiceLog ("--- " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' starting ' + $Service + ' ---')
    $ErrorActionPreference = 'Continue'
    if ($Service -eq 'backend') {
        if ((Get-MelonBackendMode $configuration) -eq 'server') {
            & (Get-MelonPython) (Join-Path $script:MelonRoot 'scripts/server-backend.py') tunnel 2>&1 | ForEach-Object { Write-ServiceLog $_ }
        } else {
            Import-MelonRuntimeConfiguration $configuration
            $java = Get-MelonJava
            & $java -jar (Join-Path $script:MelonRoot 'backend/target/rbac-service-1.0.0.jar') 2>&1 | ForEach-Object { Write-ServiceLog $_ }
        }
    } else {
        $node = (Get-Command node.exe -ErrorAction Stop).Source
        Set-Location -LiteralPath (Join-Path $script:MelonRoot 'frontend')
        & $node (Join-Path $script:MelonRoot 'frontend/node_modules/vite/bin/vite.js') --host 127.0.0.1 --port $Port --strictPort 2>&1 | ForEach-Object { Write-ServiceLog $_ }
    }
    exit $LASTEXITCODE
} catch {
    Write-ServiceLog $_.Exception.Message
    exit 1
}
