[CmdletBinding()]
param(
    [string]$KnownGoodTag = 'a75ef093bc8d',
    [string]$BackendRepository = 'gialong1416/relayforge-backend'
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $repositoryRoot '.env'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$archiveName = "relayforge-restore-drill-$suffix.dump"
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "relayforge-restore-drill-$suffix"
$archivePath = Join-Path $temporaryDirectory $archiveName
$networkName = "relayforge-restore-$suffix"
$restoreContainer = "relayforge-restore-db-$suffix"
$apiContainer = "relayforge-rollback-api-$suffix"
$restoreDatabase = 'relayforge_restore'
$restoreUser = 'relayforge_restore'
$restorePassword = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$sourceContainer = $null
$apiPort = $null

function Read-LocalSettings {
    if (-not (Test-Path -LiteralPath $settingsPath)) {
        throw 'Missing .env. Copy .env.example to .env and replace every placeholder first.'
    }

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $settingsPath) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -lt 1) {
            throw "Invalid .env line: $trimmed"
        }
        $values[$trimmed.Substring(0, $separator)] = $trimmed.Substring($separator + 1)
    }

    foreach ($required in @(
        'RELAYFORGE_DB_NAME',
        'RELAYFORGE_DB_USERNAME',
        'RELAYFORGE_API_KEY_PEPPER',
        'RELAYFORGE_ENDPOINT_ENCRYPTION_KEY',
        'RELAYFORGE_ENDPOINT_ENCRYPTION_KEY_REFERENCE'
    )) {
        if (-not $values.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($values[$required])) {
            throw "Missing $required in .env"
        }
    }
    return $values
}

function Wait-Postgres {
    param([Parameter(Mandatory)][string]$Container)

    for ($attempt = 1; $attempt -le 40; $attempt++) {
        docker exec $Container pg_isready -U $restoreUser -d $restoreDatabase *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Timed out waiting for the isolated PostgreSQL restore target.'
}

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return $listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Wait-ApiReadiness {
    param([Parameter(Mandatory)][int]$Port)

    for ($attempt = 1; $attempt -le 75; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health/readiness" -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw 'The isolated known-good backend image did not become ready against the restored database.'
}

$settings = Read-LocalSettings
New-Item -ItemType Directory -Force -Path $temporaryDirectory | Out-Null

try {
    Push-Location $repositoryRoot
    try {
        $sourceContainer = (docker compose ps -q postgres).Trim()
        if ([string]::IsNullOrWhiteSpace($sourceContainer)) {
            throw 'The local Compose PostgreSQL container is not running.'
        }

        docker compose exec -T postgres pg_dump -U $settings.RELAYFORGE_DB_USERNAME -Fc `
            -f "/tmp/$archiveName" $settings.RELAYFORGE_DB_NAME
        if ($LASTEXITCODE -ne 0) {
            throw 'Local PostgreSQL backup command failed.'
        }
        docker compose exec -T postgres pg_restore --list "/tmp/$archiveName" *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'The local custom-format backup archive failed pg_restore validation.'
        }
        docker cp "${sourceContainer}:/tmp/$archiveName" $archivePath
        if (-not (Test-Path -LiteralPath $archivePath) -or (Get-Item -LiteralPath $archivePath).Length -eq 0) {
            throw 'The local backup archive was not copied successfully.'
        }

        docker network create $networkName | Out-Null
        docker run -d --rm --name $restoreContainer --network $networkName `
            -e "POSTGRES_DB=$restoreDatabase" `
            -e "POSTGRES_USER=$restoreUser" `
            -e "POSTGRES_PASSWORD=$restorePassword" `
            postgres:17.10-alpine | Out-Null
        Wait-Postgres -Container $restoreContainer

        docker cp $archivePath "${restoreContainer}:/tmp/$archiveName"
        docker exec $restoreContainer pg_restore -U $restoreUser -d $restoreDatabase `
            --exit-on-error --no-owner --no-privileges "/tmp/$archiveName"
        if ($LASTEXITCODE -ne 0) {
            throw 'The isolated PostgreSQL restore failed.'
        }

        $validationQuery = @"
select
  (select count(*) from public.flyway_schema_history) as migration_rows,
  (select count(*) from public.owner_accounts) as owner_rows,
  (select count(*) from public.events) as event_rows,
  (select count(*) from public.deliveries) as delivery_rows,
  (select max(installed_rank) from public.flyway_schema_history) as latest_rank;
"@
        $validation = (docker exec $restoreContainer psql -U $restoreUser -d $restoreDatabase -At -F '|' -c $validationQuery).Trim()
        $parts = $validation.Split('|')
        if ($parts.Count -ne 5 -or [int]$parts[0] -lt 12 -or [int]$parts[1] -lt 1 `
            -or [int]$parts[2] -lt 1 -or [int]$parts[3] -lt 1 -or [int]$parts[4] -lt 12) {
            throw "Restored database validation failed: $validation"
        }

        docker pull "${BackendRepository}:$KnownGoodTag" | Out-Null
        $apiPort = Get-FreeLoopbackPort
        docker run -d --rm --name $apiContainer --network $networkName `
            -p "127.0.0.1:${apiPort}:8080" `
            -e "RELAYFORGE_RUNTIME=api" `
            -e "SPRING_DATASOURCE_URL=jdbc:postgresql://${restoreContainer}:5432/${restoreDatabase}" `
            -e "SPRING_DATASOURCE_USERNAME=$restoreUser" `
            -e "SPRING_DATASOURCE_PASSWORD=$restorePassword" `
            -e "RELAYFORGE_API_KEY_PEPPER=$($settings.RELAYFORGE_API_KEY_PEPPER)" `
            -e "RELAYFORGE_ENDPOINT_ENCRYPTION_KEY=$($settings.RELAYFORGE_ENDPOINT_ENCRYPTION_KEY)" `
            -e "RELAYFORGE_ENDPOINT_ENCRYPTION_KEY_REFERENCE=$($settings.RELAYFORGE_ENDPOINT_ENCRYPTION_KEY_REFERENCE)" `
            -e "RELAYFORGE_DASHBOARD_ORIGIN=http://localhost:5173" `
            -e "RELAYFORGE_SECURE_COOKIES=false" `
            -e "RELAYFORGE_PRODUCTION=false" `
            -e "RELAYFORGE_ENDPOINT_ALLOW_LOCAL_HTTP=true" `
            -e "RELAYFORGE_BOOTSTRAP_OWNER_ENABLED=false" `
            "${BackendRepository}:$KnownGoodTag" | Out-Null
        Wait-ApiReadiness -Port $apiPort

        $result = [pscustomobject]@{
            BackupFormat = 'PostgreSQL custom archive'
            Source = 'local Compose PostgreSQL only'
            RestoredMigrationRows = [int]$parts[0]
            RestoredOwnerRows = [int]$parts[1]
            RestoredEventRows = [int]$parts[2]
            RestoredDeliveryRows = [int]$parts[3]
            LatestFlywayInstalledRank = [int]$parts[4]
            CompatibilityImage = "${BackendRepository}:$KnownGoodTag"
            IsolatedApiReadiness = 'UP'
        }
        $resultDirectory = Join-Path $repositoryRoot 'performance/results'
        New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
        $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $resultDirectory 'group19-restore-drill.json') -Encoding utf8
        $result
        Write-Output 'PASS: local backup restored into an isolated database and the known-good image became ready. No EC2 resource changed.'
    } finally {
        Pop-Location
    }
} finally {
    if ($apiContainer) {
        docker rm -f $apiContainer *> $null
    }
    if ($restoreContainer) {
        docker rm -f $restoreContainer *> $null
    }
    if ($networkName) {
        docker network rm $networkName *> $null
    }
    if ($sourceContainer) {
        docker exec $sourceContainer rm -f "/tmp/$archiveName" *> $null
    }
    Remove-Item -LiteralPath $temporaryDirectory -Force -Recurse -ErrorAction SilentlyContinue
}
