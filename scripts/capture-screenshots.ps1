#!/usr/bin/env pwsh
# Re-capture all screenshots with binary-safe adb pull method
# Fixes: PowerShell > redirection corrupts PNG binary data with UTF-16 BOM

$ErrorActionPreference = "Continue"
$outDir = "d:\personal\openautolink\docs\screenshots"

function Take-Screenshot($name) {
    Start-Sleep -Milliseconds 800
    adb shell screencap -p /sdcard/oal_ss.png
    adb pull /sdcard/oal_ss.png "$outDir\$name.png" 2>&1 | Out-Null
    adb shell rm /sdcard/oal_ss.png
    Write-Host "  Saved: $name.png"
}

function Tap($x, $y) {
    adb shell input tap $x $y
    Start-Sleep -Milliseconds 600
}

function Swipe($x1, $y1, $x2, $y2, $durationMs) {
    adb shell input swipe $x1 $y1 $x2 $y2 $durationMs
    Start-Sleep -Milliseconds 500
}

function FindAndTap($text) {
    adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $c = [string](adb shell cat /sdcard/ui.xml)
    $escaped = [regex]::Escape($text)
    $pattern = "text=""$escaped""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""
    $m = [regex]::Match($c, $pattern)
    if ($m.Success) {
        $cx = [math]::Floor(([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $cy = [math]::Floor(([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        adb shell input tap $cx $cy
        Start-Sleep -Milliseconds 600
        return $true
    }
    Write-Host "  WARNING: Could not find '$text'"
    return $false
}

function FindAndTapDesc($desc) {
    adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $c = [string](adb shell cat /sdcard/ui.xml)
    $pattern = "content-desc=""$desc""[^>]*bounds=""\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""
    $m = [regex]::Match($c, $pattern)
    if ($m.Success) {
        $cx = [math]::Floor(([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
        $cy = [math]::Floor(([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
        adb shell input tap $cx $cy
        Start-Sleep -Milliseconds 600
        return $true
    }
    Write-Host "  WARNING: Could not find desc '$desc'"
    return $false
}

function FindSwitch($index) {
    # Find the Nth (0-based) checkable switch and return its center coords
    adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $c = [string](adb shell cat /sdcard/ui.xml)
    $switches = [regex]::Matches($c, 'checkable="true"[^>]+checked="([^"]+)"[^>]+bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($switches.Count -gt $index) {
        $s = $switches[$index]
        $cx = [math]::Floor(([int]$s.Groups[2].Value + [int]$s.Groups[4].Value) / 2)
        $cy = [math]::Floor(([int]$s.Groups[3].Value + [int]$s.Groups[5].Value) / 2)
        return @($cx, $cy, $s.Groups[1].Value)
    }
    return $null
}

# ============================================================
# Inject VHAL data
# ============================================================
Write-Host "`n=== Injecting VHAL data ==="
adb shell cmd car_service inject-vhal-event 0x11600207 0 0.0
adb shell cmd car_service inject-vhal-event 0x11400400 0 4
adb shell cmd car_service inject-vhal-event 0x11200402 0 false
adb shell cmd car_service inject-vhal-event 0x11200407 0 false
adb shell cmd car_service inject-vhal-event 0x11400409 0 4
adb shell cmd car_service inject-vhal-event 0x11600309 0 63.0
adb shell cmd car_service inject-vhal-event 0x11600106 0 85220.0
adb shell cmd car_service inject-vhal-event 0x1160030C 0 0.0
adb shell cmd car_service inject-vhal-event 0x11600308 0 286000.0
adb shell cmd car_service inject-vhal-event 0x11600703 0 17.0
adb shell cmd car_service inject-vhal-event 0x11400600 0 36
adb shell cmd car_service inject-vhal-event 0x1120030A 0 false
adb shell cmd car_service inject-vhal-event 0x1120030B 0 false
Start-Sleep -Seconds 2
Write-Host "VHAL injection complete"

# ============================================================
# 1. Projection Screen
# ============================================================
Write-Host "`n=== 1. Projection Screen ==="
adb shell am force-stop com.openautolink.app
Start-Sleep -Seconds 1
adb shell am start -n com.openautolink.app/.MainActivity 2>$null
Start-Sleep -Seconds 4
Take-Screenshot "01-projection-screen-idle"

# ============================================================
# 2. Settings - Connection Tab
# ============================================================
Write-Host "`n=== 2. Settings - Connection Tab ==="
FindAndTapDesc "Settings" | Out-Null
Start-Sleep -Seconds 1
Take-Screenshot "02-settings-connection-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "02-settings-connection-tab-scrolled"
Swipe 1200 200 1200 700 300

# ============================================================
# 3. Phones Tab
# ============================================================
Write-Host "`n=== 3. Phones Tab ==="
FindAndTap "Phones" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "03-settings-phones-tab"

# ============================================================
# 4. Bridge Tab
# ============================================================
Write-Host "`n=== 4. Bridge Tab ==="
FindAndTap "Bridge" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "04-settings-bridge-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "04-settings-bridge-tab-scrolled1"
Swipe 1200 200 1200 700 300

# ============================================================
# 5. Display Tab
# ============================================================
Write-Host "`n=== 5. Display Tab ==="
FindAndTap "Display" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "05-settings-display-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "05-settings-display-tab-scrolled1"
Swipe 1200 700 1200 200 300
Take-Screenshot "05-settings-display-tab-scrolled2"
Swipe 1200 200 1200 700 300
Swipe 1200 200 1200 700 300

# ============================================================
# 6. Video Tab (Auto)
# ============================================================
Write-Host "`n=== 6. Video Tab (Auto) ==="
FindAndTap "Video" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "06-settings-video-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "06-settings-video-tab-scrolled1"
Swipe 1200 200 1200 700 300

# ============================================================
# 7. Audio Tab
# ============================================================
Write-Host "`n=== 7. Audio Tab ==="
FindAndTap "Audio" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "07-settings-audio-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "07-settings-audio-tab-scrolled"
Swipe 1200 200 1200 700 300

# ============================================================
# 8. Diagnostics settings tab
# ============================================================
Write-Host "`n=== 8. Diagnostics Settings Tab ==="
FindAndTap "Diagnostics" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "08-settings-diagnostics-tab"

# ============================================================
# 9-13. Full Diagnostics Screen
# ============================================================
Write-Host "`n=== 9. Diagnostics Dashboard ==="
FindAndTap "Open Diagnostics Dashboard" | Out-Null
Start-Sleep -Seconds 1

# System tab
Take-Screenshot "09-diagnostics-system-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "09-diagnostics-system-tab-scrolled"
Swipe 1200 200 1200 700 300

# Network tab
Write-Host "`n=== 10. Network Tab ==="
FindAndTap "Network" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "10-diagnostics-network-tab"

# Bridge tab
Write-Host "`n=== 11. Bridge Tab ==="
FindAndTap "Bridge" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "11-diagnostics-bridge-tab"

# Car tab
Write-Host "`n=== 12. Car Tab ==="
FindAndTap "Car" | Out-Null
Start-Sleep -Seconds 1
Take-Screenshot "12-diagnostics-car-tab-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "12-diagnostics-car-tab-scrolled1"
Swipe 1200 200 1200 700 300

# Logs tab
Write-Host "`n=== 13. Logs Tab ==="
FindAndTap "Logs" | Out-Null
Start-Sleep -Milliseconds 500
Take-Screenshot "13-diagnostics-logs-tab"

# ============================================================
# 14. Video Tab - Manual Mode
# ============================================================
Write-Host "`n=== 14. Video Manual Mode ==="
# Go back to Settings
FindAndTapDesc "Back" | Out-Null
Start-Sleep -Milliseconds 500

# Check if we're on settings or projection
adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
$c = [string](adb shell cat /sdcard/ui.xml)
if ($c -match 'text="Video"') {
    # Already on settings
    FindAndTap "Video" | Out-Null
} else {
    # On projection, go to settings first
    FindAndTapDesc "Settings" | Out-Null
    Start-Sleep -Milliseconds 500
    FindAndTap "Video" | Out-Null
}
Start-Sleep -Milliseconds 500

# Toggle auto-negotiate off (first switch on the page)
$sw = FindSwitch 0
if ($sw -and $sw[2] -eq "true") {
    Tap $sw[0] $sw[1]
    Start-Sleep -Milliseconds 500
}

Take-Screenshot "14-settings-video-manual-top"
Swipe 1200 700 1200 200 300
Take-Screenshot "14-settings-video-manual-scrolled1"
Swipe 1200 700 1200 200 300
Take-Screenshot "14-settings-video-manual-scrolled2"

# Toggle back to auto
Swipe 1200 200 1200 700 300
Swipe 1200 200 1200 700 300
$sw = FindSwitch 0
if ($sw -and $sw[2] -eq "false") {
    Tap $sw[0] $sw[1]
}

# ============================================================
# 15-16. Safe Area & Content Inset Editors
# ============================================================
Write-Host "`n=== 15-16. Editors ==="
FindAndTap "Display" | Out-Null
Start-Sleep -Milliseconds 500

FindAndTap "Edit Safe Area" | Out-Null
Start-Sleep -Seconds 1
Take-Screenshot "15-safe-area-editor"

# Go back
adb shell input keyevent 4
Start-Sleep -Milliseconds 800

# Navigate to Content Inset editor
adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
$c = [string](adb shell cat /sdcard/ui.xml)
if (-not ($c -match 'Edit Content Insets')) {
    # Need to scroll to find it
    FindAndTap "Display" | Out-Null
    Start-Sleep -Milliseconds 500
    Swipe 1200 700 1200 400 300
    Start-Sleep -Milliseconds 300
}
FindAndTap "Edit Content Insets" | Out-Null
Start-Sleep -Seconds 1
Take-Screenshot "16-content-inset-editor"

# ============================================================
# Done — verify all files
# ============================================================
Write-Host "`n=== Verifying PNGs ==="
$allOk = $true
Get-ChildItem "$outDir\*.png" | Sort-Object Name | ForEach-Object {
    $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
    $h = ($bytes[0..3] | ForEach-Object { $_.ToString("X2") }) -join " "
    $valid = ($h -eq "89 50 4E 47")
    $mark = if ($valid) { "OK" } else { "CORRUPT" }
    Write-Host "  $mark  $($_.Name) ($([math]::Round($_.Length/1KB, 1)) KB)"
    if (-not $valid) { $allOk = $false }
}
Write-Host "`nAll valid: $allOk"
