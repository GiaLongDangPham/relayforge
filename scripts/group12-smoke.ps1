[CmdletBinding()]
param(
    [switch]$VerifyRestart
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $repositoryRoot '.env'

if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw 'Missing .env. Copy .env.example to .env and replace every placeholder first.'
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
    if (-not $settings.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($settings[$required])) {
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
    param(
        [ValidateSet('POST', 'PUT', 'PATCH', 'DELETE')][string]$Method,
        [string]$Path,
        [object]$Body,
        [hashtable]$AdditionalHeaders = @{}
    )
    $headers = Get-CsrfHeader
    foreach ($entry in $AdditionalHeaders.GetEnumerator()) {
        $headers[$entry.Key] = $entry.Value
    }
    $parameters = @{
        Uri = "$apiOrigin$Path"
        Method = $Method
        Headers = $headers
        WebSession = $session
    }
    if ($null -ne $Body) {
        $parameters['ContentType'] = 'application/json'
        $parameters['Body'] = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    return Invoke-RestMethod @parameters
}

Wait-HttpReady "$apiOrigin/api/v1/auth/csrf"
Wait-HttpReady "$receiverOrigin/health"

$loginHeaders = Get-CsrfHeader
$loginBody = @{
    loginName = $settings['RELAYFORGE_OWNER_LOGIN_NAME']
    password = $settings['RELAYFORGE_OWNER_PASSWORD']
} | ConvertTo-Json -Compress
Invoke-RestMethod -Uri "$apiOrigin/api/v1/auth/session" -Method Post -Headers $loginHeaders `
    -WebSession $session -ContentType 'application/json' -Body $loginBody | Out-Null

$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$project = Invoke-OwnerMutation -Method POST -Path '/api/v1/projects' -Body @{ name = "Compose smoke $suffix" }
$apiKey = Invoke-OwnerMutation -Method POST -Path "/api/v1/projects/$($project.id)/api-keys" `
    -Body @{ displayName = 'Compose smoke publisher' }
$endpoint = Invoke-OwnerMutation -Method POST -Path "/api/v1/projects/$($project.id)/endpoints" -Body @{
    name = 'Compose success receiver'
    destinationUrl = 'http://localhost:8081/webhooks/success'
    eventTypes = @('demo.accepted')
    enabled = $true
}

Invoke-WebRequest -UseBasicParsing -Uri "$receiverOrigin/config/signing-secret" -Method Put `
    -ContentType 'text/plain' -Body $endpoint.signingSecret | Out-Null

$idempotencyKey = [guid]::NewGuid().ToString()
$publishBody = @{ eventType = 'demo.accepted'; payload = @{ smokeId = $suffix; source = 'group12' } } `
    | ConvertTo-Json -Depth 10 -Compress
$publisherHeaders = @{
    Authorization = "Bearer $($apiKey.rawKey)"
    'Idempotency-Key' = $idempotencyKey
}
$accepted = Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($project.id)/events" -Method Post `
    -Headers $publisherHeaders -ContentType 'application/json' -Body $publishBody
$repeated = Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($project.id)/events" -Method Post `
    -Headers $publisherHeaders -ContentType 'application/json' -Body $publishBody

if ($accepted.eventId -ne $repeated.eventId -or -not $repeated.idempotentReplay) {
    throw 'Publisher idempotency did not return the original accepted event.'
}

$delivery = $null
for ($attempt = 1; $attempt -le 30; $attempt++) {
    $page = Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($project.id)/deliveries?eventId=$($accepted.eventId)&limit=20" `
        -WebSession $session
    $delivery = $page.items | Select-Object -First 1
    if ($null -ne $delivery -and $delivery.displayStatus -eq 'SUCCEEDED') {
        break
    }
    Start-Sleep -Seconds 1
}
if ($null -eq $delivery -or $delivery.displayStatus -ne 'SUCCEEDED') {
    throw "Delivery did not succeed; current status: $($delivery.displayStatus)"
}

$attempts = Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($project.id)/deliveries/$($delivery.id)/attempts" `
    -WebSession $session
$receiverRequests = Invoke-RestMethod -Uri "$receiverOrigin/requests"
$received = $receiverRequests | Where-Object { $_.eventId -eq $accepted.eventId } | Select-Object -Last 1
if ($null -eq $received -or $received.signatureValid -ne $true) {
    throw 'Receiver did not retain a valid signed request for the accepted event.'
}

if ($VerifyRestart) {
    Push-Location $repositoryRoot
    try {
        docker compose restart api worker | Out-Null
    } finally {
        Pop-Location
    }
    Wait-HttpReady "$apiOrigin/api/v1/auth/csrf"
    $owner = Invoke-RestMethod -Uri "$apiOrigin/api/v1/auth/me" -WebSession $session
    if ($owner.loginName -ne $settings['RELAYFORGE_OWNER_LOGIN_NAME']) {
        throw 'The PostgreSQL-backed owner session did not survive API restart.'
    }
    $eventAfterRestart = Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($project.id)/events/$($accepted.eventId)" `
        -WebSession $session
    if ($eventAfterRestart.event.id -ne $accepted.eventId) {
        throw 'Committed event history did not survive API/worker restart.'
    }
}

[pscustomobject]@{
    ProjectId = $project.id
    EventId = $accepted.eventId
    DeliveryId = $delivery.id
    DeliveryStatus = $delivery.displayStatus
    Attempts = @($attempts).Count
    SignatureValid = $received.signatureValid
    IdempotentPublish = $repeated.idempotentReplay
    RestartVerified = [bool]$VerifyRestart
}
