param([ValidateRange(1, 30)][int]$TimeoutSeconds = 5)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configuration = @{}
Get-Content (Join-Path $projectRoot '.env') | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $configuration[$Matches[1]] = $Matches[2] }
}
$databaseHost = $configuration['CLOUD_DB_HOST']
$databaseName = $configuration['CLOUD_DB_NAME']
if ($databaseName -notmatch '^[a-zA-Z0-9_]{1,64}$') { throw 'CLOUD_DB_NAME must be a valid schema identifier.' }
$databasePort = if ($configuration['CLOUD_DB_PORT']) { [int]$configuration['CLOUD_DB_PORT'] } else { 3306 }
if (-not $databaseHost) { throw 'Configure CLOUD_DB_HOST in .env.' }
$client = [System.Net.Sockets.TcpClient]::new()
try {
    $connection = $client.ConnectAsync($databaseHost, $databasePort)
    if (-not $connection.Wait($TimeoutSeconds * 1000)) {
        Write-Output ('MySQL TCP connection timed out after ' + $TimeoutSeconds + ' seconds. Authentication was not attempted.')
        exit 2
    }
} catch {
    Write-Output 'MySQL TCP connection failed. Authentication was not attempted.'
    exit 2
} finally {
    $client.Dispose()
}
Write-Output 'MySQL TCP port is reachable.'
if (-not $configuration['CLOUD_DB_PASSWORD']) {
    Write-Output 'CLOUD_DB_PASSWORD is not configured. Server login and MySQL credentials are separate.'
    exit 3
}
if (-not $configuration['CLOUD_DB_USERNAME']) { throw 'Configure CLOUD_DB_USERNAME in .env.' }
$mysql = Get-Command mysql.exe -ErrorAction Stop
$previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
try {
    $env:MYSQL_PWD = $configuration['CLOUD_DB_PASSWORD']
    $query = "SELECT VERSION(); SHOW SESSION STATUS LIKE 'Ssl_cipher'; SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$databaseName';"
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $mysql.Source "--host=$databaseHost" "--port=$databasePort" "--user=$($configuration['CLOUD_DB_USERNAME'])" "--connect-timeout=$TimeoutSeconds" --ssl-mode=REQUIRED --batch --skip-column-names -e $query 2>&1
        $resultCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($resultCode -eq 0) {
        Write-Output 'MySQL authentication and read-only queries succeeded.'
        $output | ForEach-Object { Write-Output $_ }
    } else {
        $errorNumber = [regex]::Match(($output -join ' '), 'ERROR\s+(\d+)').Groups[1].Value
        Write-Output ('MySQL authentication/query failed. Error code: ' + $(if ($errorNumber) { $errorNumber } else { 'unknown' }))
    }
    exit $resultCode
} finally {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
}
