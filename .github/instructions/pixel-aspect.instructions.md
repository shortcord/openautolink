---
description: "Use when modifying pixel_aspect_ratio, display scaling, video resolution, DPI, crop/letterbox mode, or any SDR video configuration. Contains the complete pixel aspect data flow and critical rules."
applyTo: "app/**"
---
# Pixel Aspect Ratio — Complete Guide

## What It Does

`pixel_aspect_ratio_e4` tells the phone's AA encoder "each pixel on the car's display is X/10000 times wider than it is tall." AA pre-shrinks its UI horizontally so that when the video is stretched to fill a wide display, circles remain circular and text is not distorted.

- `10000` = square pixels (1:1) — only correct if display AR matches video AR
- `12188` = each pixel is 1.22x wide — e.g., S21 (2340×1080, 2.17:1) showing 1920×1080 (1.78:1)
- `14454` = each pixel is 1.45x wide — e.g., Blazer EV (2914×1134, 2.57:1) showing 1920×1080
- `0` = OFF — no compensation sent (phone assumes square pixels)
- `-1` = AUTO — app computes from display/video aspect ratio at runtime

## The Formula

```
pixel_aspect_ratio_e4 = (displayAR / videoAR) × 10000
                      = (displayWidth/displayHeight) / (videoWidth/videoHeight) × 10000
```

Only needed in **crop mode** where the video is stretched to fill the full display. In **letterbox mode** the SurfaceView is constrained to 16:9 so pixels are always square.

## Data Flow (End to End)

```
AppPreferences.DEFAULT_AA_PIXEL_ASPECT = -1 (auto)
    ↓
DataStore → aaPixelAspect Flow → ProjectionViewModel combines into UiState
    ↓
ProjectionViewModel.connect() → SessionManager.start(aaPixelAspect=value)
    ↓
SessionManager.startSession(aaPixelAspect=value)
    ↓
computedPixelAspect = when {
    aaPixelAspect > 0 → aaPixelAspect     // Manual override (e.g., 14454)
    aaPixelAspect == -1 && crop → auto     // Compute from display/video AR
    else → 0                               // Off (letterbox or explicit 0)
}
    ↓
AasdkSdrConfig(pixelAspectE4 = computedPixelAspect)
    ↓
C++ readInt("pixelAspectE4") → sdrConfig_.pixelAspectE4
    ↓
SDR video_config: vc->set_pixel_aspect_ratio_e4(value) — only if > 0
    ↓
Phone AA encoder pre-shrinks UI to compensate
```

## CRITICAL RULES — DO NOT VIOLATE

### Rule 1: Default is ALWAYS -1
Every parameter default for `aaPixelAspect` must be `-1` (auto-compute):
- `AppPreferences.DEFAULT_AA_PIXEL_ASPECT = -1`
- `SessionManager.start(aaPixelAspect: Int = -1)`
- `SessionManager.startSession(aaPixelAspect: Int = -1)`
- `ProjectionViewModel.ProjectionUiState(aaPixelAspect: Int = -1)`
- `SettingsViewModel.SettingsUiState(aaPixelAspect: Int = AppPreferences.DEFAULT_AA_PIXEL_ASPECT)`

**If ANY of these defaults is changed to 0 or 10000, pixel aspect breaks silently** — circles become ovals on non-16:9 displays. The bug is invisible in logs (no error, just wrong proportions).

### Rule 2: Never "simplify" the -1/0/positive logic
The three-way value system is intentional:
- `-1` = auto (compute at runtime from actual display geometry)
- `0` = off (explicitly disabled, or letterbox mode where it's not needed)
- `> 0` = manual override (user-specified exact value)

Do NOT collapse this to a boolean. Do NOT change 0 to mean "auto". Do NOT remove the -1 sentinel.

### Rule 3: Auto-compute only in crop mode
The auto-compute path checks `scalingMode == "crop"` because:
- **Crop mode**: video fills full display → display is wider → pixels are stretched → compensation needed
- **Letterbox mode**: SurfaceView is constrained to 16:9 via `aspectRatio(16f/9f)` → no stretch → no compensation needed

### Rule 4: The SDR value must match the display's actual geometry
If the user overrides resolution or DPI, pixel_aspect must still be computed from the DISPLAY dimensions (not the video resolution). The display is the physical constant; the video resolution is what we ask the phone to encode at.

## Verification

Take a screenshot via ADB and check that circular icons (Maps, Spotify, Phone, Settings in the AA sidebar) are round, not oval. Compare width and height of any circular element — they should be equal within 2%.

```powershell
adb -s <device> shell screencap -p /sdcard/check.png
adb -s <device> pull /sdcard/check.png
```

## History

- Initial implementation: bridge-mode (always worked because bridge ran on AAOS with matching display)
- Bug 2026-04-26: defaults were 0 in SessionManager parameters while -1 everywhere else → auto-compute never triggered
- Fix: changed all parameter defaults to -1
