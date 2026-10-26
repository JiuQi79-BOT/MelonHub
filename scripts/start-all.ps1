param([switch]$NoBrowser, [switch]$Rebuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
$lock = $null
$started = @()
try {
    $lock = Enter-MelonServiceLock
    Set-Location -LiteralPath $script:MelonRoot
    $configuration = Read-MelonConfiguration
    $backendMode = Get-MelonBackendMode $configuration
    if ($backendMode -eq 'local') { Import-MelonRuntimeConfiguration $configuration }
    $services = @(Get-MelonProcesses)
    $backend = @($services | Where-Object Service -eq 'backend')
    $frontend = @($services | Where-Object Service -eq 'frontend')
    if ($backend.Count -gt 1 -or $frontend.Count -gt 1) { throw 'Multiple project instances detected. Stop them before starting the stack.' }
    if ($backend -and $backend[0].Mode -ne $backendMode) { throw 'Backend mode changed. Run stop-all before starting again.' }
    if ($backendMode -eq 'server' -and $Rebuild) { throw 'Use scripts/deploy-backend.ps1 to rebuild and deploy the server backend.' }
    if (-not $backend -and (Test-MelonPort 8080)) { throw 'Port 8080 belongs to another process. No process was stopped.' }
    $frontendPort = 5174
    if ($frontend) {
        $listener = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $frontend[0].Id | Select-Object -First 1
        if (-not $listener) { throw 'The existing frontend is not listening. Stop it and retry.' }
        if ($listener.LocalPort -ne $frontendPort) { throw 'The existing frontend must use port 5174. Stop it and restart.' }
    } else {
        if (Test-MelonPort $frontendPort) { throw 'Port 5174 belongs to another process. No process was stopped.' }
    }
    if (-not $backend -and $backendMode -eq 'local') {
        $jar = Join-Path $script:MelonRoot 'backend/target/rbac-service-1.0.0.jar'
        $inputs = @(Get-ChildItem (Join-Path $script:MelonRoot 'backend/src/main') -Recurse -File) + @(Get-Item (Join-Path $script:MelonRoot 'backend/pom.xml'), (Join-Path $script:MelonRoot 'pom.xml'))
        $needsBuild = $Rebuild -or -not (Test-Path -LiteralPath $jar)
        if (-not $needsBuild) { $needsBuild = [bool]($inputs | Where-Object LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) }
        if ($needsBuild) {
            $java = Get-MelonJava
            $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
            $maven = Get-MelonMaven
            & $maven -f (Join-Path $script:MelonRoot 'backend/pom.xml') package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'Backend build failed; services were not started.' }
        }
    } elseif ($Rebuild) { throw 'Stop the running backend before using -Rebuild.' }
    $frontendRoot = Join-Path $script:MelonRoot 'frontend'
    $installed = Join-Path $frontendRoot 'node_modules/.package-lock.json'
    $needsInstall = -not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules/vite/bin/vite.js')) -or -not (Test-Path -LiteralPath $installed)
    if (-not $needsInstall) { $needsInstall = (Get-Item -LiteralPath (Join-Path $frontendRoot 'package-lock.json')).LastWriteTimeUtc -gt (Get-Item -LiteralPath $installed).LastWriteTimeUtc }
    if ($needsInstall) {
        $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
        # Lifecycle scripts run through cmd.exe, which cannot see node when PATH exceeds 8191 characters;
        # none of the dependencies need them (esbuild ships its binary as an optional package).
        & $npm --prefix $frontendRoot ci --ignore-scripts --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    if (-not $backend) { $started += Start-MelonService 'backend' 8080 }
    Wait-MelonService 'http://127.0.0.1:8080/api/health' 'backend'
    if (-not $frontend) { $started += Start-MelonService 'frontend' $frontendPort }
    $url = "http://127.0.0.1:$frontendPort"
    Wait-MelonService $url 'frontend'
    Wait-MelonService "$url/api/health" 'backend' 15
    @{ frontendUrl = $url; backendUrl = 'http://127.0.0.1:8080'; backendMode = $backendMode; verifiedAt = (Get-Date -Format 'o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $script:MelonRoot '.local/services.json') -Encoding UTF8
    Write-Host "MelonHub ready: $url"
    Write-Host ("Backend mode: $backendMode; cloud MySQL + frontend proxy: UP")
    Write-Host 'Logs: .local/logs/backend.log and frontend.log'
    if (-not $NoBrowser) { Start-Process $url }
} catch {
    foreach ($wrapper in $started) {
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($wrapper.Id)")
        $childIds = @($children | ForEach-Object ProcessId)
        foreach ($service in @(Get-MelonProcesses | Where-Object { $_.Id -in $childIds -or $_.ParentId -in $childIds })) {
            Stop-Process -Id $service.Id -ErrorAction SilentlyContinue
        }
        if (-not $wrapper.HasExited) { Stop-Process -Id $wrapper.Id -ErrorAction SilentlyContinue }
    }
    Write-Host ('Start failed: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
