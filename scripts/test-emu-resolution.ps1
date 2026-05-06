# Helper for testing margin auto-calc across panel resolutions.
# Usage: scripts/test-emu-resolution.ps1 -Width 1920 -Height 1080 -Density 200
param(
    [Parameter(Mandatory)] [int]$Width,
    [Parameter(Mandatory)] [int]$Height,
    [Parameter(Mandatory)] [int]$Density,
    [string]$Avd = 'BlazerEV_AAOS',
    [int]$BootTimeoutSec = 300
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$emu = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
$cfg = Join-Path $env:USERPROFILE ".android\avd\$Avd.avd\config.ini"
if (-not (Test-Path $cfg)) { throw "AVD config not found: $cfg" }

Write-Host "[test-res] Stopping emulator..."
try { & $adb emu kill 2>$null | Out-Null } catch {}
Start-Sleep 3
Get-Process qemu-system-x86_64, qemu-system-aarch64 -EA SilentlyContinue | Stop-Process -Force -EA SilentlyContinue
Start-Sleep 2

Write-Host "[test-res] Patching $cfg : ${Width}x${Height} @ ${Density}dpi"
$lines = Get-Content $cfg
$lines = $lines | ForEach-Object {
    switch -Regex ($_) {
        '^hw\.lcd\.width=' { "hw.lcd.width=$Width" }
        '^hw\.lcd\.height=' { "hw.lcd.height=$Height" }
        '^hw\.lcd\.density=' { "hw.lcd.density=$Density" }
        default { $_ }
    }
}
Set-Content -Path $cfg -Value $lines -Encoding ASCII

Write-Host "[test-res] Cold-booting emulator..."
Start-Process $emu -ArgumentList @('-avd', $Avd, '-no-snapshot-load') -WindowStyle Hidden

& $adb wait-for-device
$booted = $false
for ($i = 0; $i -lt ($BootTimeoutSec / 5); $i++) {
    $b = & $adb shell getprop sys.boot_completed 2>$null
    if ($b -match '1') { $booted = $true; break }
    Start-Sleep 5
}
if (-not $booted) { throw "Emulator failed to boot within $BootTimeoutSec sec" }
Write-Host "[test-res] Booted."

& $adb shell wm size
& $adb shell wm density

# Pre-set prefs we always want for emulator testing.
$pkg = 'com.openautolink.app'
$a = 'com.openautolink.app.SET_PREF'
& $adb shell am broadcast -a $a -p $pkg --es key direct_transport --es svalue hotspot | Out-Null
& $adb shell am broadcast -a $a -p $pkg --es key manual_ip_enabled --ez bvalue true | Out-Null
& $adb shell am broadcast -a $a -p $pkg --es key manual_ip_address --es svalue 10.0.2.2 | Out-Null
& $adb shell am broadcast -a $a -p $pkg --es key video_codec --es svalue h264 | Out-Null
& $adb shell am broadcast -a $a -p $pkg --es key video_scaling_mode --es svalue crop | Out-Null
& $adb shell am broadcast -a $a -p $pkg --es key aa_auto_margins --ez bvalue true | Out-Null

Write-Host "[test-res] Done. Now: .\gradlew :app:installDebug; adb shell monkey -p com.openautolink.app -c android.intent.category.LAUNCHER 1"
