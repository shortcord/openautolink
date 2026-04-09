<#
.SYNOPSIS
    Creates a GitHub Release that triggers the CI bridge build.

.DESCRIPTION
    - Reads version from secrets/version.properties
    - Creates a GitHub release with tag v<versionName>
    - The release-bridge.yml CI workflow triggers on publish and cross-compiles
      the bridge binary on GitHub Actions, then attaches it to this release
    - The AAB is NOT uploaded - it goes to Google Play Console separately
    - Requires: gh CLI authenticated

.EXAMPLE
    .\scripts\create-release.ps1
    .\scripts\create-release.ps1 -Notes "Fixed video codec negotiation"
#>
param(
    [string]$Notes = "",
    [switch]$Draft
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

# Read version
$versionFile = Join-Path $repoRoot 'secrets\version.properties'
if (-not (Test-Path $versionFile)) {
    throw "Version file not found: $versionFile. Run bundle-release.ps1 first."
}

$version = @{}
Get-Content $versionFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') {
        $version[$Matches[1]] = $Matches[2]
    }
}

$tag = "v$($version['versionName'])"
Write-Host "[release] Creating release: $tag"

# Create release — CI (release-bridge.yml) will build and attach the bridge binary
$ghArgs = @('release', 'create', $tag, '--title', $tag)
if ($Draft) { $ghArgs += '--draft' }
if ($Notes) {
    $ghArgs += '--notes'
    $ghArgs += $Notes
} else {
    $ghArgs += '--generate-notes'
}

Write-Host "[release] gh $($ghArgs -join ' ')"
& gh @ghArgs

Write-Host ""
Write-Host "[release] Release $tag created"
Write-Host "[release] CI will build and attach the bridge binary (release-bridge.yml)"
