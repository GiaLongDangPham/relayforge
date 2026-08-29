[CmdletBinding()]
param(
    [ValidateSet('all', 'retry', 'timeout', 'crash', 'exhaustion')]
    [string]$Scenario = 'all'
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$settingsPath = Join-Path $repositoryRoot '.env'
$apiOrigin = 'http://localhost:8080'
$receiverOrigin = 'http://localhost:8081'
$workerManagementOrigin = 'http://localhost:8082'
$prometheusOrigin = 'http://localhost:9090'
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

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
    param(
        [Parameter(Mandatory)][string]$Uri,
        [int]$Attempts = 60
    )

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
        [object]$Body
    )

    $parameters = @{
        Uri = "$apiOrigin$Path"
        Method = $Method
        Headers = Get-CsrfHeader
        WebSession = $session
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Invoke-Publish {
    param(
        [Parameter(Mandatory)]$Project,
        [Parameter(Mandatory)]$ApiKey,
        [Parameter(Mandatory)][string]$EventType,
        [Parameter(Mandatory)][string]$Label
    )

    $body = @{
        eventType = $EventType
        payload = @{
            exercise = 'group18'
            scenario = $Label
            runId = [guid]::NewGuid().ToString()
        }
    } | ConvertTo-Json -Depth 10 -Compress

    return Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($Project.id)/events" -Method Post `
        -Headers @{ Authorization = "Bearer $($ApiKey.rawKey)"; 'Idempotency-Key' = [guid]::NewGuid().ToString() } `
        -ContentType 'application/json' -Body $body
}

function Get-Delivery {
    param(
        [Parameter(Mandatory)]$Project,
        [Parameter(Mandatory)]$Event
    )

    $page = Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($Project.id)/deliveries?eventId=$($Event.eventId)&limit=20" `
        -WebSession $session
    $delivery = @($page.items) | Select-Object -First 1
    if ($null -eq $delivery) {
        throw "No delivery is visible for event $($Event.eventId)."
    }
    return $delivery
}

function Get-Attempts {
    param(
        [Parameter(Mandatory)]$Project,
        [Parameter(Mandatory)]$Delivery
    )

    return @(Invoke-RestMethod -Uri "$apiOrigin/api/v1/projects/$($Project.id)/deliveries/$($Delivery.id)/attempts" `
        -WebSession $session)
}

function Wait-For {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [Parameter(Mandatory)][string]$Description,
        [int]$Attempts = 60,
        [int]$DelaySeconds = 1
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $result = & $Condition
        if ($null -ne $result) {
            return $result
        }
        Start-Sleep -Seconds $DelaySeconds
    }
    throw "Timed out waiting for $Description"
}

function New-ScenarioFixture {
    param(
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][string]$DestinationUrl
    )

    $suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $eventType = "group18.$Label"
    $project = Invoke-OwnerMutation -Method POST -Path '/api/v1/projects' -Body @{ name = "Group 18 $Label $suffix" }
    $apiKey = Invoke-OwnerMutation -Method POST -Path "/api/v1/projects/$($project.id)/api-keys" `
        -Body @{ displayName = "Group 18 $Label publisher" }
    $endpoint = Invoke-OwnerMutation -Method POST -Path "/api/v1/projects/$($project.id)/endpoints" -Body @{
        name = "Group 18 $Label receiver"
        destinationUrl = $DestinationUrl
        eventTypes = @($eventType)
        enabled = $true
    }

    Invoke-WebRequest -UseBasicParsing -Uri "$receiverOrigin/config/signing-secret" -Method Put `
        -ContentType 'text/plain' -Body $endpoint.signingSecret | Out-Null

    return [pscustomobject]@{
        Project = $project
        ApiKey = $apiKey
        Endpoint = $endpoint
        EventType = $eventType
    }
}

function Set-EndpointDestination {
    param(
        [Parameter(Mandatory)]$Fixture,
        [Parameter(Mandatory)][string]$DestinationUrl
    )

    $endpoint = Invoke-OwnerMutation -Method PUT `
        -Path "/api/v1/projects/$($Fixture.Project.id)/endpoints/$($Fixture.Endpoint.id)" `
        -Body @{
            name = $Fixture.Endpoint.name
            destinationUrl = $DestinationUrl
            eventTypes = @($Fixture.EventType)
            version = $Fixture.Endpoint.version
        }
    $Fixture.Endpoint = $endpoint
}

function Invoke-RetryableFailureScenario {
    $fixture = New-ScenarioFixture -Label 'retryable-5xx' -DestinationUrl "$receiverOrigin/webhooks/fail"
    $event = Invoke-Publish -Project $fixture.Project -ApiKey $fixture.ApiKey -EventType $fixture.EventType -Label 'retryable-5xx'

    $retryDelivery = Wait-For -Description 'a retry scheduled after HTTP 500' -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        if ($delivery.displayStatus -eq 'RETRY_SCHEDULED' -and $attempts.Count -eq 1 `
            -and $attempts[0].status -eq 'RETRYABLE_FAILURE' -and $attempts[0].httpStatus -eq 500) {
            return $delivery
        }
        return $null
    }

    Set-EndpointDestination -Fixture $fixture -DestinationUrl "$receiverOrigin/webhooks/success"
    $successDelivery = Wait-For -Description 'the corrected endpoint to succeed on its next retry' -Attempts 30 -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        if ($delivery.displayStatus -eq 'SUCCEEDED' -and $attempts.Count -eq 2 `
            -and $attempts[0].status -eq 'RETRYABLE_FAILURE' -and $attempts[1].status -eq 'SUCCEEDED') {
            return $delivery
        }
        return $null
    }

    $finalAttempts = Get-Attempts -Project $fixture.Project -Delivery $successDelivery
    return [pscustomobject]@{
        Scenario = 'retryable-5xx'
        EventId = $event.eventId
        DeliveryId = $successDelivery.id
        AttemptStatuses = @($finalAttempts | ForEach-Object { $_.status })
        FirstHttpStatus = $finalAttempts[0].httpStatus
        FinalDisplayStatus = $successDelivery.displayStatus
    }
}

function Invoke-TimeoutScenario {
    $fixture = New-ScenarioFixture -Label 'timeout' -DestinationUrl "$receiverOrigin/webhooks/slow"
    $event = Invoke-Publish -Project $fixture.Project -ApiKey $fixture.ApiKey -EventType $fixture.EventType -Label 'timeout'

    $timedOutDelivery = Wait-For -Description 'a retry scheduled after the dispatch deadline' -Attempts 25 -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        if ($delivery.displayStatus -eq 'RETRY_SCHEDULED' -and $attempts.Count -eq 1 `
            -and $attempts[0].status -eq 'RETRYABLE_FAILURE' -and $attempts[0].failureCode -eq 'DISPATCH_TIMEOUT') {
            return $delivery
        }
        return $null
    }

    Set-EndpointDestination -Fixture $fixture -DestinationUrl "$receiverOrigin/webhooks/success"
    $successDelivery = Wait-For -Description 'the corrected timeout endpoint to succeed on retry' -Attempts 30 -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        if ($delivery.displayStatus -eq 'SUCCEEDED' -and $attempts.Count -eq 2 `
            -and $attempts[0].failureCode -eq 'DISPATCH_TIMEOUT' -and $attempts[1].status -eq 'SUCCEEDED') {
            return $delivery
        }
        return $null
    }

    $finalAttempts = Get-Attempts -Project $fixture.Project -Delivery $successDelivery
    return [pscustomobject]@{
        Scenario = 'timeout'
        EventId = $event.eventId
        DeliveryId = $successDelivery.id
        AttemptStatuses = @($finalAttempts | ForEach-Object { $_.status })
        FirstFailureCode = $finalAttempts[0].failureCode
        FinalDisplayStatus = $successDelivery.displayStatus
    }
}

function Invoke-CrashRecoveryScenario {
    $fixture = New-ScenarioFixture -Label 'crash-recovery' -DestinationUrl "$receiverOrigin/webhooks/slow"
    $event = Invoke-Publish -Project $fixture.Project -ApiKey $fixture.ApiKey -EventType $fixture.EventType -Label 'crash-recovery'

    Wait-For -Description 'the slow receiver to observe the started request' -Attempts 20 -Condition {
        $requests = @(Invoke-RestMethod -Uri "$receiverOrigin/requests")
        if (@($requests | Where-Object { $_.eventId -eq $event.eventId -and $_.mode -eq 'slow' }).Count -gt 0) {
            return $true
        }
        return $null
    } | Out-Null

    Push-Location $repositoryRoot
    try {
        docker compose kill -s KILL worker | Out-Null
        docker compose up -d worker | Out-Null
    } finally {
        Pop-Location
    }
    Wait-HttpReady "$workerManagementOrigin/actuator/health/readiness" 30

    $unknownDelivery = Wait-For -Description 'lease recovery to preserve the started attempt as UNKNOWN' -Attempts 45 -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        if (@($attempts | Where-Object { $_.attemptNumber -eq 1 -and $_.status -eq 'UNKNOWN' }).Count -eq 1) {
            return $delivery
        }
        return $null
    }

    Set-EndpointDestination -Fixture $fixture -DestinationUrl "$receiverOrigin/webhooks/success"
    $successDelivery = Wait-For -Description 'the recovered delivery to succeed without rewriting UNKNOWN' -Attempts 35 -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        if ($delivery.displayStatus -eq 'SUCCEEDED' -and $attempts.Count -eq 2 `
            -and $attempts[0].status -eq 'UNKNOWN' -and $attempts[1].status -eq 'SUCCEEDED') {
            return $delivery
        }
        return $null
    }

    $finalAttempts = Get-Attempts -Project $fixture.Project -Delivery $successDelivery
    return [pscustomobject]@{
        Scenario = 'crash-recovery'
        EventId = $event.eventId
        DeliveryId = $successDelivery.id
        AttemptStatuses = @($finalAttempts | ForEach-Object { $_.status })
        FinalDisplayStatus = $successDelivery.displayStatus
    }
}

function Invoke-ExhaustionScenario {
    $fixture = New-ScenarioFixture -Label 'exhaustion' -DestinationUrl "$receiverOrigin/webhooks/fail"
    $event = Invoke-Publish -Project $fixture.Project -ApiKey $fixture.ApiKey -EventType $fixture.EventType -Label 'exhaustion'

    $exhaustedDelivery = Wait-For -Description 'five retryable failures to exhaust the bounded attempt budget' -Attempts 390 -Condition {
        $delivery = Get-Delivery -Project $fixture.Project -Event $event
        $attempts = Get-Attempts -Project $fixture.Project -Delivery $delivery
        $allRetryable500 = @($attempts | Where-Object {
            $_.status -eq 'RETRYABLE_FAILURE' -and $_.httpStatus -eq 500
        }).Count -eq 5
        if ($delivery.displayStatus -eq 'EXHAUSTED' -and $attempts.Count -eq 5 -and $allRetryable500) {
            return $delivery
        }
        return $null
    }

    $finalAttempts = Get-Attempts -Project $fixture.Project -Delivery $exhaustedDelivery
    return [pscustomobject]@{
        Scenario = 'exhaustion'
        EventId = $event.eventId
        DeliveryId = $exhaustedDelivery.id
        AttemptStatuses = @($finalAttempts | ForEach-Object { $_.status })
        AttemptCount = $finalAttempts.Count
        FinalDisplayStatus = $exhaustedDelivery.displayStatus
    }
}

$settings = Read-LocalSettings
Wait-HttpReady "$apiOrigin/api/v1/auth/csrf"
Wait-HttpReady "$receiverOrigin/health"
Wait-HttpReady "$workerManagementOrigin/actuator/health/readiness"
Wait-HttpReady "$prometheusOrigin/-/ready"

$login = Invoke-RestMethod -Uri "$apiOrigin/api/v1/auth/session" -Method Post -Headers (Get-CsrfHeader) `
    -WebSession $session -ContentType 'application/json' -Body (@{
        loginName = $settings.RELAYFORGE_OWNER_LOGIN_NAME
        password = $settings.RELAYFORGE_OWNER_PASSWORD
    } | ConvertTo-Json -Compress)
if ([string]::IsNullOrWhiteSpace($login.ownerId)) {
    throw 'Owner login did not return an owner identity.'
}

$results = @()
if ($Scenario -in @('all', 'retry')) {
    $results += Invoke-RetryableFailureScenario
}
if ($Scenario -in @('all', 'timeout')) {
    $results += Invoke-TimeoutScenario
}
if ($Scenario -in @('all', 'crash')) {
    $results += Invoke-CrashRecoveryScenario
}
if ($Scenario -in @('all', 'exhaustion')) {
    $results += Invoke-ExhaustionScenario
}

$metricQueries = @(
    'relayforge_delivery_backlog{state="ready"}',
    'relayforge_worker_permits_available',
    'sum(hikaricp_connections_pending)'
)
$metrics = @{}
foreach ($query in $metricQueries) {
    $response = Invoke-RestMethod -Uri "$prometheusOrigin/api/v1/query?query=$([uri]::EscapeDataString($query))"
    $metrics[$query] = @($response.data.result)
}

$resultDirectory = Join-Path $repositoryRoot 'performance/results'
New-Item -ItemType Directory -Force -Path $resultDirectory | Out-Null
$resultName = if ($Scenario -eq 'all') { 'group18-failure-recovery.json' } else { "group18-$Scenario.json" }
$resultPath = Join-Path $resultDirectory $resultName
@{
    completedAt = [DateTimeOffset]::UtcNow.ToString('O')
    scenarios = $results
    metricSnapshot = $metrics
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $resultPath -Encoding utf8

$results | Select-Object Scenario, EventId, DeliveryId, Attempts, FinalDisplayStatus
Write-Output 'PASS: Group 18 local failure and recovery evidence completed. Raw API keys and signing secrets were not printed.'
