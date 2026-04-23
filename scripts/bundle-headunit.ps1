<#
.SYNOPSIS
    Builds a signed release AAB of headunit-revived with the OpenAutoLink package name.

.DESCRIPTION
    Uses the same keystore and DPAPI credentials as the main app.
    Builds the 'playstore' flavor (minSdk 21).

.EXAMPLE
    .\scripts\bundle-headunit.ps1
#>

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$huRoot = Join-Path $repoRoot 'external\headunit-revived'
$credentialsPath = Join-Path $repoRoot 'secrets\signing-credentials.xml'
$keystorePath = Join-Path $repoRoot 'secrets\upload-key.jks'

if (-not (Test-Path $huRoot)) {
    throw "headunit-revived not found at $huRoot"
}
if (-not (Test-Path $keystorePath)) {
    throw "Keystore not found: $keystorePath"
}

# --- Credentials ---

function ConvertTo-PlainText {
    param([Parameter(Mandatory)][Security.SecureString]$SecureString)
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

if (-not (Test-Path $credentialsPath)) {
    throw "No saved credentials found. Run save-signing-credentials.ps1 first."
}

Write-Host '[headunit] Using saved DPAPI credentials'
$cred = Import-Clixml -Path $credentialsPath
$storePassword = ConvertTo-PlainText -SecureString $cred.StorePassword
$keyPassword = ConvertTo-PlainText -SecureString $cred.KeyPassword
$keyAlias = if ($cred.KeyAlias) { $cred.KeyAlias } else { 'upload' }

try {
    $env:UPLOAD_STORE_PASSWORD = $storePassword
    $env:UPLOAD_KEY_PASSWORD = $keyPassword
    $env:UPLOAD_KEY_ALIAS = $keyAlias

    # Ensure local.properties exists
    $localProps = Join-Path $huRoot 'local.properties'
    $sdkPath = "$env:LOCALAPPDATA\Android\Sdk"
    $escapedSdk = $sdkPath.Replace('\', '\\').Replace(':', '\:')
    Set-Content -Path $localProps -Value "sdk.dir=$escapedSdk" -Encoding ASCII

    # Build
    $gradlew = Join-Path $huRoot 'gradlew.bat'
    $javaHome = 'C:\Program Files\Android\Android Studio\jbr'
    if (-not (Test-Path $javaHome)) {
        $javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
    }

    Write-Host "[headunit] Building AAB (githubRelease)..."
    Write-Host "[headunit] Java: $javaHome"
    Write-Host "[headunit] Project: $huRoot"

    $env:JAVA_HOME = $javaHome

    Push-Location $huRoot
    & $gradlew bundleGithubRelease `
        "-PoalVersionCode=2" `
        "-PoalVersionName=1.0.1"
    Pop-Location

    $aabPath = Join-Path $huRoot 'app\build\outputs\bundle\githubRelease'
    Write-Host ""
    Write-Host "[headunit] AAB output: $aabPath"
    Get-ChildItem $aabPath -Filter '*.aab' | ForEach-Object {
        Write-Host "[headunit]   $($_.Name) ($([math]::Round($_.Length / 1MB, 2)) MB)"
    }
}
finally {
    $storePassword = $null
    $keyPassword = $null
    Remove-Item Env:\UPLOAD_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\UPLOAD_KEY_ALIAS -ErrorAction SilentlyContinue
}
