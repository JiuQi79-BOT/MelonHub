$script:MelonRoot = Split-Path $PSScriptRoot -Parent

function Read-MelonConfiguration {
    $file = Join-Path $script:MelonRoot '.env'
    if (-not (Test-Path -LiteralPath $file)) { throw 'Create the root .env from .env.example first.' }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $file -Encoding UTF8) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}

function Import-MelonRuntimeConfiguration($Values) {
    foreach ($name in @('CLOUD_DB_HOST','CLOUD_DB_PORT','CLOUD_DB_NAME','CLOUD_DB_USERNAME','CLOUD_DB_PASSWORD','LOCAL_DB_HOST','LOCAL_DB_PORT','LOCAL_DB_NAME','LOCAL_DB_USERNAME','LOCAL_DB_PASSWORD','LOCAL_DB_CONTAINER','ADMIN_USERNAME','ADMIN_PASSWORD','SPRING_PROFILES_ACTIVE','SEED_DEMO','SERVER_PORT','SERVER_ADDRESS','CACHE_ENABLED','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','REDIS_DATABASE','REDIS_CONTAINER','APP_RATE_LIMIT','DB_POOL_SIZE','AUDIT_RETENTION_DAYS','ADMIN_PASSWORD_RESET','ADMIN_IDLE_MINUTES','MANAGEMENT_PORT','HTTP_MAX_THREADS','GLOBAL_RATE_LIMIT','WEBHOOK_DELIVERY_ENABLED','WEBHOOK_RETENTION_DAYS')) {
        # PowerShell passes $null to .NET string parameters as '', which would create empty variables
        # that override Spring defaults (an empty SERVER_ADDRESS binds every interface).
        if ($Values[$name]) { Set-Item -LiteralPath ("Env:" + $name) -Value $Values[$name] }
        else { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
    }
    if ($Values['SPRING_PROFILES_ACTIVE'] -notin @('cloud','local')) { throw 'SPRING_PROFILES_ACTIVE must be cloud or local in .env.' }
    $prefix = if ($Values['SPRING_PROFILES_ACTIVE'] -eq 'local') { 'LOCAL_DB_' } else { 'CLOUD_DB_' }
    foreach ($name in @(($prefix + 'HOST'),($prefix + 'PORT'),($prefix + 'NAME'),($prefix + 'USERNAME'),($prefix + 'PASSWORD'),'ADMIN_PASSWORD')) {
        if (-not $Values[$name]) { throw "$name must be configured in .env." }
    }
    if ($Values[$prefix + 'NAME'] -ne 'melonhub_rbac') { throw ($prefix + 'NAME must be melonhub_rbac.') }
    if ($prefix -eq 'LOCAL_DB_' -and $Values['LOCAL_DB_HOST'] -notin @('127.0.0.1','localhost')) { throw 'LOCAL_DB_HOST must be 127.0.0.1 or localhost.' }
    if ($Values['ADMIN_PASSWORD'].Length -lt 12) { throw 'ADMIN_PASSWORD must contain at least 12 characters.' }
    if ($Values['CACHE_ENABLED'] -ne 'false' -and -not $Values['REDIS_PASSWORD']) { throw 'REDIS_PASSWORD must be configured in .env, or set CACHE_ENABLED=false.' }
    if ($Values['SERVER_PORT'] -and $Values['SERVER_PORT'] -ne '8080') { throw 'The frontend proxy requires SERVER_PORT=8080.' }
    if ($Values['SERVER_ADDRESS'] -and $Values['SERVER_ADDRESS'] -ne '127.0.0.1') { throw 'The local launcher requires SERVER_ADDRESS=127.0.0.1.' }
    $env:SEED_DEMO = 'false'
}

function Start-MelonDatabase {
    if ($env:SPRING_PROFILES_ACTIVE -ne 'local' -or -not $env:LOCAL_DB_CONTAINER) { return }
    # Inspect only the named existing container; never recreate a database or volume.
    $raw = & docker inspect --format '{{json .State}}' $env:LOCAL_DB_CONTAINER 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'The configured local MySQL container is unavailable. Start Docker Desktop and check LOCAL_DB_CONTAINER.' }
    $state = $raw | ConvertFrom-Json
    if (-not $state.Running) {
        if ($state.Status -notin @('exited','created')) { throw 'The local MySQL container is not in a startable state.' }
        & docker start $env:LOCAL_DB_CONTAINER | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the existing local MySQL container.' }
    }
}

function Start-MelonRedis {
    if ($env:CACHE_ENABLED -eq 'false' -or -not $env:REDIS_CONTAINER) { return }
    if ($env:REDIS_HOST -notin @('127.0.0.1','localhost')) { return }
    if (-not $env:REDIS_PASSWORD) { throw 'REDIS_PASSWORD must be configured in .env when the Redis cache is enabled.' }
    $port = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { '6380' }
    $folder = Join-Path $script:MelonRoot '.local/redis'
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $conf = Join-Path $folder 'redis.conf'
    # Pure cache: no RDB/AOF, so a restart can never resurrect entries that missed an invalidation.
    $desired = @('bind 0.0.0.0', 'protected-mode yes', 'port 6379', ('requirepass ' + $env:REDIS_PASSWORD), 'save ""', 'appendonly no', 'maxmemory 256mb', 'maxmemory-policy volatile-lru') -join "`n"
    $current = if (Test-Path -LiteralPath $conf) { ((Get-Content -LiteralPath $conf -Raw) -replace "`r", '').TrimEnd() } else { '' }
    $changed = $current -ne $desired
    if ($changed) { [IO.File]::WriteAllText($conf, $desired + "`n", (New-Object System.Text.UTF8Encoding($false))) }
    $raw = & docker inspect --format '{{json .}}' $env:REDIS_CONTAINER 2>$null
    if ($LASTEXITCODE -ne 0) {
        if ((Get-Command docker -ErrorAction SilentlyContinue) -and (& docker info --format '{{.ServerVersion}}' 2>$null)) {
            & docker run -d --name $env:REDIS_CONTAINER --label com.melonhub.role=cache -p ("127.0.0.1:" + $port + ":6379") -v ($folder + ':/usr/local/etc/redis:ro') redis:7-alpine redis-server /usr/local/etc/redis/redis.conf | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not create the MelonHub Redis container.' }
        } else { throw 'Docker is not available. Start Docker Desktop for the MelonHub Redis cache.' }
    } else {
        $info = $raw | ConvertFrom-Json
        if ($info.Config.Labels.'com.melonhub.role' -ne 'cache') { throw 'REDIS_CONTAINER names a container that is not the MelonHub cache. No container was changed.' }
        if (-not $info.State.Running) {
            & docker start $env:REDIS_CONTAINER | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not start the MelonHub Redis container.' }
        } elseif ($changed) {
            & docker restart $env:REDIS_CONTAINER | Out-Null
        }
    }
    $deadline = (Get-Date).AddSeconds(20)
    do {
        $client = New-Object System.Net.Sockets.TcpClient
        try { $client.Connect('127.0.0.1', [int]$port); return } catch { Start-Sleep -Milliseconds 300 } finally { $client.Dispose() }
    } while ((Get-Date) -lt $deadline)
    throw 'The MelonHub Redis cache did not open its port.'
}

function Stop-MelonRedis {
    if (-not $env:REDIS_CONTAINER) { return }
    $raw = & docker inspect --format '{{json .}}' $env:REDIS_CONTAINER 2>$null
    if ($LASTEXITCODE -ne 0) { return }
    $info = $raw | ConvertFrom-Json
    if ($info.Config.Labels.'com.melonhub.role' -ne 'cache' -or -not $info.State.Running) { return }
    & docker stop $env:REDIS_CONTAINER | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host 'Stopped MelonHub Redis cache container.' }
}

function Get-MelonMaven {
    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($directory in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ($directory -and (Test-Path -LiteralPath (Join-Path $directory 'bin/mvn.cmd'))) { return (Join-Path $directory 'bin/mvn.cmd') }
    }
    $cache = Join-Path $env:USERPROFILE '.m2/wrapper/dists'
    if (Test-Path -LiteralPath $cache) {
        $cached = Get-ChildItem -LiteralPath $cache -Filter mvn.cmd -Recurse -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($cached) { return $cached.FullName }
    }
    throw 'Maven was not found. Add mvn.cmd to PATH or configure MAVEN_HOME.'
}

function Get-MelonJava {
    foreach ($candidate in @($(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' }), 'D:\jdk21\bin\java.exe')) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return (Get-Command java.exe -ErrorAction Stop).Source
}

function Get-MelonProcesses {
    $marker = $script:MelonRoot.Replace('\','/').TrimEnd('/')
    foreach ($process in Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='node.exe'") {
        if (-not $process.CommandLine) { continue }
        $command = $process.CommandLine.Replace('\','/') -replace '/+', '/'
        $command = $command.Replace('/node_modules/.bin/../','/node_modules/')
        $service = $null
        if ($process.Name -eq 'node.exe' -and $command.IndexOf("$marker/frontend/node_modules/vite/bin/vite.js", [StringComparison]::OrdinalIgnoreCase) -ge 0) { $service = 'frontend' }
        if ($process.Name -eq 'java.exe' -and (
            $command.IndexOf("$marker/backend/target/rbac-service-1.0.0.jar", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            ($command.Contains('com.melonhub.rbac.RbacApplication') -and (
                $command.IndexOf("$marker/backend/target/classes", [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $command.IndexOf("$marker/out/production/", [StringComparison]::OrdinalIgnoreCase) -ge 0)))) { $service = 'backend' }
        if ($service) { [pscustomobject]@{ Id = $process.ProcessId; Service = $service; Created = $process.CreationDate } }
    }
}

function Enter-MelonServiceLock {
    $folder = Join-Path $script:MelonRoot '.local'
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    try { return [IO.File]::Open((Join-Path $folder 'services.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another MelonHub start/stop operation is running. Try again after it finishes.' }
}

function Test-MelonPort([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Wait-MelonService([string]$Url, [string]$Service, [int]$Seconds = 90) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            if ($Service -eq 'backend') {
                $health = Invoke-RestMethod -Uri $Url -TimeoutSec 3
                if ($health.status -eq 'UP' -and $health.database -eq 'UP' -and $health.model -eq 'RBAC0') { return }
            } else {
                $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
                if ($response.StatusCode -eq 200 -and $response.Content.Contains('MelonHub')) { return }
            }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "$Service did not become ready. Check .local/logs/$Service.log."
}

function Start-MelonService([string]$Service, [int]$Port) {
    $shell = Join-Path $PSHOME 'pwsh.exe'
    if (-not (Test-Path -LiteralPath $shell)) { $shell = Join-Path $PSHOME 'powershell.exe' }
    $runner = Join-Path $script:MelonRoot 'scripts/run-service.ps1'
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $runner + '"'),'-Service',$Service,'-Port',$Port)
    return Start-Process -FilePath $shell -ArgumentList $arguments -WorkingDirectory $script:MelonRoot -WindowStyle Hidden -PassThru
}
