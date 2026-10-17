param([switch]$NoBrowser, [switch]$Rebuild)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'service-tools.ps1')
$lock = $null
$started = @()
try {
    $lock = Enter-MelonServiceLock
    Set-Location -LiteralPath $script:MelonRoot
    Import-MelonRuntimeConfiguration (Read-MelonConfiguration)
    $services = @(Get-MelonProcesses)
    $backend = @($services | Where-Object Service -eq 'backend')
    $frontend = @($services | Where-Object Service -eq 'frontend')
    if ($backend.Count -gt 1 -or $frontend.Count -gt 1) { throw 'Multiple project instances detected. Stop them before starting the stack.' }
    if (-not $backend -and (Test-MelonPort 8080)) { throw 'Port 8080 belongs to another process. No process was stopped.' }
    $frontendPort = 5174
    if ($frontend) {
        $listener = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $frontend[0].Id | Select-Object -First 1
        if (-not $listener) { throw 'The existing frontend is not listening. Stop it and retry.' }
        if ($listener.LocalPort -ne $frontendPort) { throw 'The existing frontend must use port 5174. Stop it and restart.' }
    } else {
        if (Test-MelonPort $frontendPort) { throw 'Port 5174 belongs to another process. No process was stopped.' }
    }
    if (-not $backend) {
        $jar = Join-Path $script:MelonRoot 'backend/target/rbac-service-1.0.0.jar'
        $inputs = @(Get-ChildItem (Join-Path $script:MelonRoot 'backend/src/main') -Recurse -File) + @(Get-Item (Join-Path $script:MelonRoot 'backend/pom.xml'), (Join-Path $script:MelonRoot 'pom.xml'))
        $needsBuild = $Rebuild -or -not (Test-Path -LiteralPath $jar)
        if (-not $needsBuild) { $needsBuild = [bool]($inputs | Where-Object LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc) }
        if ($needsBuild) {
            $java = Get-MelonJava
            $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
            $maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
            & $maven -f (Join-Path $script:MelonRoot 'backend/pom.xml') package -DskipTests
            if ($LASTEXITCODE -ne 0) { throw 'Backend build failed; services were not started.' }
        }
    } elseif ($Rebuild) { throw 'Stop the running backend before using -Rebuild.' }
    if (-not (Test-Path -LiteralPath (Join-Path $script:MelonRoot 'frontend/node_modules/vite/bin/vite.js'))) {
        $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
        & $npm --prefix (Join-Path $script:MelonRoot 'frontend') ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    if (-not $backend) { $started += Start-MelonService 'backend' 8080 }
    Wait-MelonService 'http://127.0.0.1:8080/api/health' 'backend'
    if (-not $frontend) { $started += Start-MelonService 'frontend' $frontendPort }
    $url = "http://127.0.0.1:$frontendPort"
    Wait-MelonService $url 'frontend'
    Wait-MelonService "$url/api/health" 'backend' 15
    @{ frontendUrl = $url; backendUrl = 'http://127.0.0.1:8080'; verifiedAt = (Get-Date -Format 'o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $script:MelonRoot '.local/services.json') -Encoding UTF8
    Write-Host "MelonHub ready: $url"
    Write-Host 'Backend + cloud database + frontend proxy: UP'
    Write-Host 'Logs: .local/logs/backend.log and frontend.log'
    if (-not $NoBrowser) { Start-Process $url }
} catch {
    foreach ($wrapper in $started) {
        $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($wrapper.Id)")
        foreach ($child in $children) {
            if (@(Get-MelonProcesses | Where-Object Id -eq $child.ProcessId)) { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
        }
        if (-not $wrapper.HasExited) { Stop-Process -Id $wrapper.Id -ErrorAction SilentlyContinue }
    }
    Write-Host ('Start failed: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
