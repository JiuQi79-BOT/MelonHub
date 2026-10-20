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
    foreach ($name in @('CLOUD_DB_HOST','CLOUD_DB_PORT','CLOUD_DB_NAME','CLOUD_DB_USERNAME','CLOUD_DB_PASSWORD','ADMIN_USERNAME','ADMIN_PASSWORD','SPRING_PROFILES_ACTIVE','SEED_DEMO','SERVER_PORT','SERVER_ADDRESS','CACHE_ENABLED','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','REDIS_DATABASE','APP_RATE_LIMIT','DB_POOL_SIZE','AUDIT_RETENTION_DAYS','ADMIN_PASSWORD_RESET','ADMIN_IDLE_MINUTES','MANAGEMENT_PORT','HTTP_MAX_THREADS','GLOBAL_RATE_LIMIT','WEBHOOK_DELIVERY_ENABLED','WEBHOOK_RETENTION_DAYS')) {
        # PowerShell passes $null to .NET string parameters as '', which would create empty variables
        # that override Spring defaults (an empty SERVER_ADDRESS binds every interface).
        if ($Values[$name]) { Set-Item -LiteralPath ("Env:" + $name) -Value $Values[$name] }
        else { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
    }
    if ($Values['SPRING_PROFILES_ACTIVE'] -and $Values['SPRING_PROFILES_ACTIVE'] -ne 'cloud') { throw 'Only the cloud MySQL profile is supported. Set SPRING_PROFILES_ACTIVE=cloud in .env.' }
    $env:SPRING_PROFILES_ACTIVE = 'cloud'
    $prefix = 'CLOUD_DB_'
    foreach ($name in @(($prefix + 'HOST'),($prefix + 'PORT'),($prefix + 'NAME'),($prefix + 'USERNAME'),($prefix + 'PASSWORD'),'ADMIN_PASSWORD')) {
        if (-not $Values[$name]) { throw "$name must be configured in .env." }
    }
    if ($Values[$prefix + 'NAME'] -ne 'melonhub_rbac') { throw ($prefix + 'NAME must be melonhub_rbac.') }
    if ($Values['ADMIN_PASSWORD'].Length -lt 12) { throw 'ADMIN_PASSWORD must contain at least 12 characters.' }
    if ($Values['CACHE_ENABLED'] -eq 'true' -and (-not $Values['REDIS_HOST'] -or -not $Values['REDIS_PASSWORD'])) { throw 'Configure the server REDIS_HOST and REDIS_PASSWORD, or set CACHE_ENABLED=false.' }
    if ($Values['SERVER_PORT'] -and $Values['SERVER_PORT'] -ne '8080') { throw 'The frontend proxy requires SERVER_PORT=8080.' }
    if ($Values['SERVER_ADDRESS'] -and $Values['SERVER_ADDRESS'] -ne '127.0.0.1') { throw 'The local launcher requires SERVER_ADDRESS=127.0.0.1.' }
    $env:SEED_DEMO = 'false'
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
