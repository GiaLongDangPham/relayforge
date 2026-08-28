[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PublicOrigin,

    [switch]$Push
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workingTreeChanges = @(& git status --porcelain)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not inspect the Git working tree before deriving an image tag.'
}
if ($workingTreeChanges.Count -gt 0) {
    throw 'Refusing to build a Git-SHA-tagged release image from a dirty working tree. Commit or stash the changes first.'
}

$origin = [Uri]$PublicOrigin
if (-not $origin.IsAbsoluteUri -or $origin.Scheme -ne 'https' -or -not [string]::IsNullOrEmpty($origin.Query) -or -not [string]::IsNullOrEmpty($origin.Fragment)) {
    throw 'PublicOrigin must be an absolute HTTPS origin without a query string or fragment, for example https://relayforge.example.com.'
}

$tag = (& git rev-parse --short=12 HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $tag -notmatch '^[0-9a-f]{7,64}$') {
    throw 'Could not derive an immutable Git commit tag from HEAD.'
}

$backendImage = "gialong1416/relayforge-backend:$tag"
$gatewayImage = "gialong1416/relayforge-gateway:$tag"

function Invoke-Docker {
    param([Parameter(Mandatory)][string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($Arguments -join ' ')"
    }
}

Invoke-Docker -Arguments @('build', '--tag', $backendImage, 'backend')
Invoke-Docker -Arguments @('build', '--build-arg', "VITE_API_ORIGIN=$($origin.GetLeftPart([UriPartial]::Authority))", '--file', 'deploy/Dockerfile.gateway', '--tag', $gatewayImage, '.')
Invoke-Docker -Arguments @('image', 'inspect', $backendImage, $gatewayImage)

if ($Push) {
    Invoke-Docker -Arguments @('push', $backendImage)
    Invoke-Docker -Arguments @('push', $gatewayImage)
}
else {
    Write-Host "Built locally without a registry mutation: $backendImage and $gatewayImage"
    Write-Host 'After Docker Hub login and explicit approval, rerun with -Push.'
}
