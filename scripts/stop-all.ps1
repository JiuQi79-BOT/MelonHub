$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
$lock = $null
try {
    $lock = Enter-MelonServiceLock
    $services = @(Get-MelonProcesses)
    foreach ($service in $services) {
        $current = Get-MelonProcesses | Where-Object { $_.Id -eq $service.Id -and $_.Created -eq $service.Created }
        if ($current) {
            Stop-Process -Id $service.Id -ErrorAction Stop
            Write-Host ("Stopped MelonHub " + $service.Service + ' (PID ' + $service.Id + ')')
        }
    }
    $deadline = (Get-Date).AddSeconds(15)
    while (@(Get-MelonProcesses).Count -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (@(Get-MelonProcesses).Count) { throw 'Some project processes are still running.' }
    Remove-Item -LiteralPath (Join-Path $script:MelonRoot '.local/services.json') -ErrorAction SilentlyContinue
    Write-Host 'MelonHub stopped. Shared databases and unrelated services were not stopped.'
} catch {
    Write-Host ('Stop failed: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
