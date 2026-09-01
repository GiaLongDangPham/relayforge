[CmdletBinding()]
param(
    [ValidateRange(4, 10)]
    [int]$PollCycles = 4,
    [ValidateRange(0, 4999)]
    [int]$InitialPollOffsetMilliseconds = 2500
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $repositoryRoot '.env'
$resultDirectory = Join-Path $repositoryRoot 'performance/results'
$resultPath = Join-Path $resultDirectory 'dashboard-polling.json'
$apiOrigin = 'http://localhost:8080'
$receiverOrigin = 'http://localhost:8081'
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$pollInterval = [TimeSpan]::FromSeconds(5)

$routeTemplates = @(
    '/api/v1/projects/{projectId}/events',
    '/api/v1/projects/{projectId}/events/{eventId}',
    '/api/v1/projects/{projectId}/deliveries',
    '/api/v1/projects/{projectId}/deliveries/{deliveryId}',
    '/api/v1/projects/{projectId}/deliveries/{deliveryId}/attempts'
)

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

    foreach ($required in @('RELAYFORGE_OWNER_LOGIN_NAME', 'RELAYFORGE_OWNER_PASSWORD')) {
        if (-not $values.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($values[$required])) {
            throw "Missing $required in .env"
        }
    }
    return $values
}

function Wait-HttpReady {
    param([Parameter(Mandatory)][string]$Uri, [int]$Attempts = 60)

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
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][object]$Body)

    return Invoke-RestMethod -Uri "$apiOrigin$Path" -Method Post -Headers (Get-CsrfHeader) -WebSession $session `
        -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 10 -Compress)
}

function Invoke-TimedGet {
    param([Parameter(Mandatory)][string]$Path)

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$apiOrigin$Path" -WebSession $session
    $watch.Stop()
    return [pscustomobject]@{
        Body = $response.Content | ConvertFrom-Json -DateKind String
        ElapsedMilliseconds = $watch.Elapsed.TotalMilliseconds
    }
}

function Wait-ForStartedAttempt {
    param([Parameter(Mandatory)]$Project, [Parameter(Mandatory)]$Event)

    for ($attempt = 1; $attempt -le 80; $attempt++) {
        $deliveryPage = Invoke-TimedGet "/api/v1/projects/$($Project.id)/deliveries?eventId=$($Event.eventId)&limit=20"
        $delivery = @($deliveryPage.Body.items) | Select-Object -First 1
        if ($null -ne $delivery) {
            $attempts = Invoke-TimedGet "/api/v1/projects/$($Project.id)/deliveries/$($delivery.id)/attempts"
            $started = @($attempts.Body) | Where-Object { $_.status -eq 'STARTED' } | Select-Object -First 1
            if ($null -ne $started) {
                return [pscustomobject]@{ Delivery = $delivery; Attempt = $started }
            }
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'Timed out waiting for the controlled slow receiver attempt to enter STARTED.'
}

function Get-RouteCounters {
    $content = (Invoke-WebRequest -UseBasicParsing -Uri "$apiOrigin/actuator/prometheus").Content
    $counters = @{}
    foreach ($template in $routeTemplates) {
        $counters[$template] = 0
    }

    foreach ($line in ($content -split "`n")) {
        if ($line -match '^http_server_requests_seconds_count\{.*method="GET".*status="200".*uri="([^"]+)".*\}\s+([0-9.Ee+-]+)$') {
            $uri = $matches[1]
            if ($counters.ContainsKey($uri)) {
                $counters[$uri] += [double]$matches[2]
            }
        }
    }
    return $counters
}

function Get-Percentile {
    param([Parameter(Mandatory)][double[]]$Values, [ValidateRange(0.0, 1.0)][double]$Percentile)

    if ($Values.Count -eq 0) {
        return $null
    }
    $ordered = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $ordered.Count) - 1
    return [Math]::Round($ordered[[Math]::Max(0, $index)], 3)
}

function New-MeasurementFixture {
    $suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $eventType = 'dashboard.polling.measurement'
    $project = Invoke-OwnerMutation -Path '/api/v1/projects' -Body @{ name = "Dashboard polling $suffix" }
    $apiKey = Invoke-OwnerMutation -Path "/api/v1/projects/$($project.id)/api-keys" -Body @{ displayName = 'Dashboard polling measurement publisher' }
    $endpoint = Invoke-OwnerMutation -Path "/api/v1/projects/$($project.id)/endpoints" -Body @{
        name = 'Dashboard polling slow receiver'
        destinationUrl = "$receiverOrigin/webhooks/slow"
        eventTypes = @($eventType)
        enabled = $true
    }

    Invoke-WebRequest -UseBasicParsing -Uri "$receiverOrigin/config/signing-secret" -Method Put `
        -ContentType 'text/plain' -Body $endpoint.signingSecret | Out-Null

    return [pscustomobject]@{ Project = $project; ApiKey = $apiKey; EventType = $eventType }
}

function Invoke-Publish {
    param([Parameter(Mandatory)]$Fixture)

    $body = @{
        eventType = $Fixture.EventType
        payload = @{ measurement = 'dashboard-polling'; run = [guid]::NewGuid().ToString() }
    } | ConvertTo-Json -Depth 10 -Compress

    return Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($Fixture.Project.id)/events" -Method Post `
        -Headers @{ Authorization = "Bearer $($Fixture.ApiKey.rawKey)"; 'Idempotency-Key' = [guid]::NewGuid().ToString() } `
        -ContentType 'application/json' -Body $body
}

$settings = Read-LocalSettings
Wait-HttpReady "$apiOrigin/actuator/health/readiness"
Wait-HttpReady "$receiverOrigin/health"

Invoke-RestMethod -Uri "$apiOrigin/api/v1/auth/session" -Method Post -Headers (Get-CsrfHeader) -WebSession $session `
    -ContentType 'application/json' -Body (@{
        loginName = $settings.RELAYFORGE_OWNER_LOGIN_NAME
        password = $settings.RELAYFORGE_OWNER_PASSWORD
    } | ConvertTo-Json -Compress) | Out-Null

$fixture = New-MeasurementFixture
$event = Invoke-Publish -Fixture $fixture
$started = Wait-ForStartedAttempt -Project $fixture.Project -Event $event

# Dashboard selection performs this one detail read; its query has no refetch interval.
Invoke-TimedGet "/api/v1/projects/$($fixture.Project.id)/deliveries/$($started.Delivery.id)/attempts/$($started.Attempt.id)" | Out-Null

$beforeCounters = Get-RouteCounters
$routeTimings = @{}
foreach ($template in $routeTemplates) {
    $routeTimings[$template] = [System.Collections.Generic.List[double]]::new()
}

$visibilityDelayMilliseconds = $null
$observedTransition = $false
$nextPollAt = [DateTimeOffset]::UtcNow.AddMilliseconds($InitialPollOffsetMilliseconds)

for ($cycle = 1; $cycle -le $PollCycles; $cycle++) {
    $remaining = $nextPollAt - [DateTimeOffset]::UtcNow
    if ($remaining.TotalMilliseconds -gt 0) {
        Start-Sleep -Milliseconds ([int][Math]::Ceiling($remaining.TotalMilliseconds))
    }

    $events = Invoke-TimedGet "/api/v1/projects/$($fixture.Project.id)/events?limit=20"
    $routeTimings['/api/v1/projects/{projectId}/events'].Add($events.ElapsedMilliseconds)
    $eventDetails = Invoke-TimedGet "/api/v1/projects/$($fixture.Project.id)/events/$($event.eventId)"
    $routeTimings['/api/v1/projects/{projectId}/events/{eventId}'].Add($eventDetails.ElapsedMilliseconds)
    $deliveries = Invoke-TimedGet "/api/v1/projects/$($fixture.Project.id)/deliveries?eventId=$($event.eventId)&limit=20"
    $routeTimings['/api/v1/projects/{projectId}/deliveries'].Add($deliveries.ElapsedMilliseconds)
    $delivery = @($deliveries.Body.items) | Select-Object -First 1
    if ($null -eq $delivery) {
        throw 'The controlled delivery disappeared during the polling measurement.'
    }
    $deliveryDetails = Invoke-TimedGet "/api/v1/projects/$($fixture.Project.id)/deliveries/$($delivery.id)"
    $routeTimings['/api/v1/projects/{projectId}/deliveries/{deliveryId}'].Add($deliveryDetails.ElapsedMilliseconds)
    $attempts = Invoke-TimedGet "/api/v1/projects/$($fixture.Project.id)/deliveries/$($delivery.id)/attempts"
    $routeTimings['/api/v1/projects/{projectId}/deliveries/{deliveryId}/attempts'].Add($attempts.ElapsedMilliseconds)

    if (-not $observedTransition -and $delivery.displayStatus -eq 'RETRY_SCHEDULED') {
        $completedAttempt = @($attempts.Body) | Where-Object { $_.status -eq 'RETRYABLE_FAILURE' } | Select-Object -First 1
        if ($null -ne $completedAttempt) {
            $finishedAt = [DateTimeOffset]::Parse($completedAttempt.finishedAt)
            $visibilityDelayMilliseconds = [Math]::Round(([DateTimeOffset]::UtcNow - $finishedAt).TotalMilliseconds, 3)
            $observedTransition = $true
        }
    }
    $nextPollAt = $nextPollAt.Add($pollInterval)
}

$afterCounters = Get-RouteCounters
$metricDeltas = @{}
foreach ($template in $routeTemplates) {
    $metricDeltas[$template] = $afterCounters[$template] - $beforeCounters[$template]
}

$roundTrips = @{}
foreach ($template in $routeTemplates) {
    $values = $routeTimings[$template].ToArray()
    $roundTrips[$template] = [ordered]@{
        samples = $values.Count
        p50Milliseconds = Get-Percentile -Values $values -Percentile 0.5
        p95Milliseconds = Get-Percentile -Values $values -Percentile 0.95
        maxMilliseconds = if ($values.Count -eq 0) { $null } else { [Math]::Round(($values | Measure-Object -Maximum).Maximum, 3) }
    }
}

New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
$result = [ordered]@{
    completedAt = [DateTimeOffset]::UtcNow.ToString('O')
    pollIntervalSeconds = $pollInterval.TotalSeconds
    initialPollOffsetMilliseconds = $InitialPollOffsetMilliseconds
    pollCycles = $PollCycles
    steadyStateRecurringQueries = $routeTemplates.Count
    expectedRecurringRequests = $routeTemplates.Count * $PollCycles
    observedRecurringRequests = @($metricDeltas.Values | Measure-Object -Sum).Sum
    metricDeltaByRoute = $metricDeltas
    clientRoundTripMilliseconds = $roundTrips
    transitionObserved = $observedTransition
    transitionVisibilityDelayMilliseconds = $visibilityDelayMilliseconds
    limitations = @(
        'One local authenticated dashboard-equivalent session and one slow local receiver; not a production load or capacity claim.',
        'The timing is the delay from persisted terminal-attempt time to the next observed delivery-list poll, not an end-user SLA.',
        'The harness reproduces recurring REST reads; it does not render or automate a real authenticated browser session.'
    )
}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resultPath -Encoding utf8

[pscustomobject]@{
    PollCycles = $result.pollCycles
    InitialPollOffsetMilliseconds = $result.initialPollOffsetMilliseconds
    RecurringQueriesPerCycle = $result.steadyStateRecurringQueries
    ExpectedRecurringRequests = $result.expectedRecurringRequests
    ObservedRecurringRequests = $result.observedRecurringRequests
    TransitionObserved = $result.transitionObserved
    TransitionVisibilityDelayMilliseconds = $result.transitionVisibilityDelayMilliseconds
    ResultFile = 'performance/results/dashboard-polling.json (ignored)'
}
Write-Output 'PASS: Dashboard polling baseline completed. Credentials, raw API keys, signing secrets, IDs, payloads, and URLs were not printed.'
