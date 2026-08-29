[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^(?:25[0-5]|2[0-4][0-9]|1?[0-9][0-9]?)(?:\.(?:25[0-5]|2[0-4][0-9]|1?[0-9][0-9]?)){3}$')]
    [string]$ExpectedIpv4,

    [string]$Domain = 'gialong.duckdns.org'
)

$ErrorActionPreference = 'Stop'

$addresses = @(Resolve-DnsName -Name $Domain -Type A -ErrorAction Stop |
    Where-Object { $_.Type -eq 'A' } |
    Select-Object -ExpandProperty IPAddress -Unique)
if ($addresses -notcontains $ExpectedIpv4) {
    throw "DuckDNS mismatch: expected $ExpectedIpv4 but resolved $($addresses -join ', '). Update DuckDNS before deployment."
}

$response = Invoke-WebRequest -UseBasicParsing -Uri "https://$Domain/" -TimeoutSec 15
if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
    throw "HTTPS did not return a successful response for ${Domain}: $($response.StatusCode)"
}

[pscustomobject]@{
    Domain = $Domain
    ExpectedIpv4 = $ExpectedIpv4
    ResolvedIpv4 = $addresses
    HttpsStatus = $response.StatusCode
}
Write-Output 'PASS: DuckDNS DNS and public HTTPS are ready for a deployment. This script did not change DNS, EC2, or GitHub.'
