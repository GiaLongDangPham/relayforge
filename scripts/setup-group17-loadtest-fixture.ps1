[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $repositoryRoot '.env'
$outputPath = Join-Path $repositoryRoot 'performance/.loadtest.env'

if (!(Test-Path -LiteralPath $settingsPath)) {
    throw 'Missing .env. Copy .env.example to .env and replace every placeholder first.'
}
if ((Test-Path -LiteralPath $outputPath) -and !$Force) {
    throw 'performance/.loadtest.env already exists. Use -Force to replace it with a new local benchmark fixture.'
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $settingsPath) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
        continue
    }
    $separator = $trimmed.IndexOf('=')
    if ($separator -lt 1) {
        throw "Invalid .env line: $trimmed"
    }
    $settings[$trimmed.Substring(0, $separator)] = $trimmed.Substring($separator + 1)
}
foreach ($required in @('RELAYFORGE_OWNER_LOGIN_NAME', 'RELAYFORGE_OWNER_PASSWORD')) {
    if (!$settings.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($settings[$required])) {
        throw "Missing $required in .env"
    }
}

$apiOrigin = 'http://localhost:8080'
$receiverOrigin = 'http://localhost:8081'
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

function Wait-HttpReady {
    param([string]$Uri, [int]$Attempts = 60)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "Timed out waiting for $Uri"
}

function Get-CsrfHeader {
    $csrf = Invoke-RestMethod -Uri "$apiOrigin/api/v1/auth/csrf" -WebSession $session
    return @{ $csrf.headerName = $csrf.token }
}

function Invoke-OwnerMutation {
    param([string]$Path, [object]$Body)
    $headers = Get-CsrfHeader
    return Invoke-RestMethod -Uri "$apiOrigin$Path" -Method Post -Headers $headers -WebSession $session `
        -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 10 -Compress)
}

Wait-HttpReady "$apiOrigin/actuator/health/readiness"
Wait-HttpReady "$receiverOrigin/health"

$loginHeaders = Get-CsrfHeader
$loginBody = @{ loginName = $settings['RELAYFORGE_OWNER_LOGIN_NAME']; password = $settings['RELAYFORGE_OWNER_PASSWORD'] } `
    | ConvertTo-Json -Compress
Invoke-RestMethod -Uri "$apiOrigin/api/v1/auth/session" -Method Post -Headers $loginHeaders -WebSession $session `
    -ContentType 'application/json' -Body $loginBody | Out-Null

$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$eventType = 'performance.accepted'
$project = Invoke-OwnerMutation -Path '/api/v1/projects' -Body @{ name = "Performance fixture $suffix" }
$apiKey = Invoke-OwnerMutation -Path "/api/v1/projects/$($project.id)/api-keys" -Body @{ displayName = 'Group 17 local k6 publisher' }
$endpoint = Invoke-OwnerMutation -Path "/api/v1/projects/$($project.id)/endpoints" -Body @{
    name = 'Group 17 success receiver'
    destinationUrl = 'http://localhost:8081/webhooks/success'
    eventTypes = @($eventType)
    enabled = $true
}

Invoke-WebRequest -UseBasicParsing -Uri "$receiverOrigin/config/signing-secret" -Method Put `
    -ContentType 'text/plain' -Body $endpoint.signingSecret | Out-Null

@(
    "K6_PROJECT_ID=$($project.id)"
    "K6_PUBLISHER_API_KEY=$($apiKey.rawKey)"
    "K6_EVENT_TYPE=$eventType"
    "K6_RUN_ID=group17-$suffix"
) | Set-Content -LiteralPath $outputPath -Encoding utf8NoBOM

$apiKey = $null
$endpoint.signingSecret = $null

[pscustomobject]@{
    ProjectId = $project.id
    EventType = $eventType
    FixtureFile = 'performance/.loadtest.env (ignored; contains the one-time publisher key)'
    Receiver = 'http://localhost:8081/webhooks/success'
}
