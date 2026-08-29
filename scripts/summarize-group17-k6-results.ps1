[CmdletBinding()]
param(
    [string]$SummaryPath = 'performance/results/k6-summary.json'
)

$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $SummaryPath)) {
    throw "Missing k6 summary: $SummaryPath"
}

$summary = Get-Content -Raw -LiteralPath $SummaryPath | ConvertFrom-Json
$thresholdFailures = @()
foreach ($metricProperty in $summary.metrics.psobject.Properties) {
    $metric = $metricProperty.Value
    if ($null -eq $metric.thresholds) {
        continue
    }
    foreach ($threshold in $metric.thresholds.psobject.Properties) {
        # k6 uses true to mean a threshold has failed, not that it passed.
        if ($threshold.Value -eq $true) {
            $thresholdFailures += "$($metricProperty.Name): $($threshold.Name)"
        }
    }
}

[pscustomobject]@{
    Requests = $summary.metrics.http_reqs.count
    FailedRequestRate = $summary.metrics.http_req_failed.value
    PublishP50Milliseconds = $summary.metrics.relayforge_publish_acceptance_seconds.med * 1000
    PublishP95Milliseconds = $summary.metrics.relayforge_publish_acceptance_seconds.'p(95)' * 1000
    HttpP95Milliseconds = $summary.metrics.http_req_duration.'p(95)'
    CheckRate = $summary.metrics.checks.value
    ThresholdsPassed = ($thresholdFailures.Count -eq 0)
    FailedThresholds = if ($thresholdFailures.Count -eq 0) { '(none)' } else { $thresholdFailures -join '; ' }
}
