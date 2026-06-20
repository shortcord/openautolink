# OpenAutoLink — Local Testing Guide

> **Last updated: June 19, 2026 — refreshed for the current app/companion architecture.**
>
> The historical SBC bridge test bench (ports `5288/5289/5290`, SBC hardware) has been
> replaced by the direct app/companion workflow. The active setup is the AAOS app
> connecting to the phone companion on TCP `5277`, with discovery on mDNS, UDP `5279`,
> and TCP identity probe `5278`; see [architecture.md](architecture.md) and
> [networking.md](networking.md). The emulator setup, VHAL testing, and remote
> diagnostics sections below are current and useful.

## Overview

Full end-to-end testing requires the AAOS app talking to the phone companion over a
shared WiFi network. Since the GM head unit has **no ADB access** due to GM restrictions,
we use the Android SDK AAOS emulator as a stand-in. The phone companion runs on a
physical Android phone or a second emulator instance.

```
┌──────────────────┐         ┌─────────────────────────────────────┐
│  Dev PC           │         │  Phone (or second emulator)          │
│                   │         │                                     │
│  AAOS Emulator    │◄──WiFi──►  Companion app on TCP :5277         │
│  (192.168.x.x)    │  same   │  mDNS _openautolink._tcp            │
│                   │  network│  Identity probe :5278                │
│  adb → emulator   │         │  UDP discovery :5279                │
└──────────────────┘         └─────────────────────────────────────┘
```

## Prerequisites

- Android SDK with emulator and platform-tools installed
- AAOS emulator AVDs (see Emulator Setup below):
  - **`BlazerEV_AAOS`** — Android 33 Automotive (Google APIs, no Play Store), x86_64, 2914×1134. Supports `adb root` and VHAL injection. Primary testing image.
  - **`BlazerEV_AAOS_14`** — Android 14 Automotive (Google APIs, no Play Store), x86_64, 2914×1134. Supports `adb root` and VHAL injection. AAOS 14 compatibility testing.
  - **`DD_AAOS_33`** — Android 33 Automotive Distant Display (Google Play), x86_64, 2914×1134 + cluster displays. For visual cluster testing. No `adb root`.
- A phone with Android Auto running the companion app for full end-to-end tests
- Both devices on the same WiFi network (phone hotspot or shared network)

## 1. Companion Setup

The phone companion app handles TCP listening, identity probes, and Android Auto launch.

### Install the Companion

Build and install the companion APK on your phone:

```bash
cd companion
../gradlew assembleDebug
adb install -r build/outputs/apk/debug/*.apk
```

### Configure Connection Mode

1. Open the Companion app on your phone
2. Set the connection mode to match your test setup (Phone Hotspot or Car Hotspot)
3. If using Phone Hotspot mode: enable the phone's WiFi hotspot
4. If using Car Hotspot mode: configure the car's WiFi SSID in the companion settings

### Start the Companion Service

Tap **Start** in the companion app. The service begins listening on:
- TCP `5277` — main AA byte pipe
- TCP `5278` — identity probe responder
- UDP `5279` — broadcast discovery responder
- mDNS `_openautolink._tcp` — service advertisement

## 3. Emulator Setup

### Starting the Emulator

We use **three AVDs** for complete testing coverage:

| AVD | Image | Root | VHAL Injection | Cluster Visual | Use For |
|-----|-------|------|---------------|----------------|---------|
| **`BlazerEV_AAOS`** | `android-33;android-automotive;x86_64` | Yes | Yes | Logs only | Primary: VHAL, video, audio, session, reconnect |
| **`BlazerEV_AAOS_14`** | `android-34-ext9;android-automotive;x86_64` | Yes | Yes | Logs only | AAOS 14 compatibility testing |
| **`DD_AAOS_33`** | `android-33;android-automotive-distant-display-playstore;x86_64` | No | No | Yes (visible panel) | Visual cluster verification |

All AVDs use **2914×1134 @ 200dpi** to match the real GM Blazer EV display.

#### Creating the AVDs (one-time)

```powershell
# Install system images
sdkmanager "system-images;android-33;android-automotive;x86_64"
sdkmanager "system-images;android-33;android-automotive-distant-display-playstore;x86_64"
sdkmanager "system-images;android-34-ext9;android-automotive;x86_64"

# Create AVDs
echo "no" | avdmanager create avd --name "BlazerEV_AAOS" `
  --package "system-images;android-33;android-automotive;x86_64" `
  --device "automotive_1080p_landscape" --force

echo "no" | avdmanager create avd --name "BlazerEV_AAOS_14" `
  --package "system-images;android-34-ext9;android-automotive;x86_64" `
  --device "automotive_1080p_landscape" --force

echo "no" | avdmanager create avd --name "DD_AAOS_33" `
  --package "system-images;android-33;android-automotive-distant-display-playstore;x86_64" `
  --device "automotive_distant_display_with_play" --force
```

Then edit each AVD's `config.ini` (`~/.android/avd/<name>.avd/config.ini`):
- Set `hw.lcd.width=2914`, `hw.lcd.height=1134`, `hw.lcd.density=200` to match the Blazer EV
- For `DD_AAOS_33`: shrink the secondary displays to avoid an oversized window:
  ```
  hw.display6.height=240
  hw.display6.width=400
  hw.display7.height=240
  hw.display7.width=800
  ```

#### Launching

```powershell
# Primary testing (VHAL + root):
emulator -avd BlazerEV_AAOS -no-audio -gpu swiftshader_indirect -no-boot-anim

# AAOS 14 compatibility testing:
emulator -avd BlazerEV_AAOS_14 -no-audio -gpu swiftshader_indirect -no-boot-anim

# Cluster visual testing (distant display):
emulator -avd DD_AAOS_33 -no-audio -gpu swiftshader_indirect -no-boot-anim
```

#### VHAL Testing (BlazerEV_AAOS only)

The non-Play Store image supports `adb root` and VHAL property injection:

```powershell
# Get root access
adb root

# Grant car permissions (required — declared in manifest)
adb shell "pm grant com.openautolink.app android.car.permission.CAR_SPEED"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_ENERGY"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_POWERTRAIN"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_EXTERIOR_ENVIRONMENT"
adb shell "pm grant com.openautolink.app android.car.permission.CAR_INFO"

# Inject Blazer EV-like values
adb shell "cmd car_service inject-vhal-event 291504647 0.0"      # speed: 0 m/s
adb shell "cmd car_service inject-vhal-event 289408000 4"        # gear: PARK
adb shell "cmd car_service inject-vhal-event 291504905 41550.0"  # EV battery: 41550 Wh
adb shell "cmd car_service inject-vhal-event 291505923 13.0"     # outside temp: 13°C
adb shell "cmd car_service inject-vhal-event 291504904 214984.4" # range: 214984 m
adb shell "cmd car_service inject-vhal-event 287310855 0"        # night mode: off
adb shell "cmd car_service inject-vhal-event 287310850 0"        # parking brake: off

# Simulate driving
adb shell "cmd car_service inject-vhal-event 291504647 16.7"     # speed: 60 km/h
adb shell "cmd car_service inject-vhal-event 289408000 8"        # gear: DRIVE
```

> **Note:** VHAL injection requires the non-Play Store image (`BlazerEV_AAOS`). The distant display image (`DD_AAOS_33`) blocks `inject-vhal-event` with a security exception.

### Configuring the Emulator's Network for Companion Traffic

The emulator and phone companion must be on the same network. The simplest approach is to use the host PC's network with `adb reverse`:

```powershell
# Make the emulator see the host's localhost as its own
# The companion runs on the phone, reachable at the phone's IP on the shared network
# Or use adb reverse if running a second emulator as the companion:
adb reverse tcp:5277 tcp:5277
```

For testing with a real phone, ensure both the emulator host and phone are on the same WiFi network. The app discovers the companion via mDNS, UDP broadcast, or TCP identity probe — no manual IP configuration needed in most cases.

## 4. Installing and Running the App

### Build and Install

```powershell
# Build debug APK
.\gradlew :app:assembleDebug

# Install to running emulator
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Launch the App

```powershell
adb shell am start -n com.openautolink.app/.ui.MainActivity
```

### View Logs

```powershell
# All app logs
adb logcat --pid=$(adb shell pidof com.openautolink.app)

# Filter for OpenAutoLink tags
adb logcat -s OAL:* Transport:* Video:* Audio:*
```

## 5. End-to-End Test Workflow

1. **Start the companion** on your phone — tap Start in the Companion app
2. **Start emulator** — launch `BlazerEV_AAOS`
3. **Ensure same network** — phone and emulator host on the same WiFi
4. **Install app** — `adb install` the debug APK
5. **Launch app** — app discovers the companion via mDNS/UDP/TCP probe and connects
6. **Verify projection** — video should render in the emulator, audio should play, touch should respond

### What to Verify

| Component | How to Test | Expected Behavior |
|-----------|-------------|-------------------|
| **Discovery** | App status UI / logcat | Phone discovered via mDNS, UDP, or TCP probe |
| **TCP connection** | App status UI / logcat | App connects to companion on port 5277 |
| **Video** | Emulator display | Phone screen projected, smooth rendering |
| **Audio** | Emulator audio output | Media/nav audio plays through correct purposes |
| **Touch** | Click/drag in emulator | Touch events forwarded to phone, UI responds |
| **Reconnection** | Kill companion, restart | App shows "Connecting...", reconnects when companion returns |
| **Settings** | App settings screen | Codec, display, and connection preferences persisted via DataStore |

## 7. Unit and Integration Tests

These run without hardware:

```powershell
# Unit tests (no emulator needed)
.\gradlew :app:testDebugUnitTest

# Instrumentation tests (requires running emulator)
.\gradlew :app:connectedDebugAndroidTest
```

## 8. Mock Bridge Testing (Historical — Bridge-Mode Only)

> **Note:** The mock bridge (`scripts/mock_bridge.py`) speaks the OAL protocol (ports 5288/5289/5290)
> which is the historical bridge-mode protocol. It is **not** used with the current app/companion
> direct mode (port 5277). This section is preserved for developers working on the `bridge-mode` branch.

The mock bridge lets you test the app's full video/audio/control pipeline without a physical SBC, phone, or car. It speaks OAL protocol directly to the app, generating synthetic H.264 video (via ffmpeg) and PCM audio (sine wave).

### What You Can Test

| Area | What the mock provides |
|------|----------------------|
| **TCP connection + handshake** | Hello exchange, phone_connected event, config_echo |
| **Video rendering** | H.264 test pattern with timestamp — verifies MediaCodec init, SPS/PPS parsing, frame rendering |
| **Audio playback** | 48kHz stereo PCM sine wave on media purpose — verifies AudioTrack creation, purpose routing |
| **Session state machine** | IDLE → CONNECTING → BRIDGE_CONNECTED → PHONE_CONNECTED → STREAMING transitions |
| **Reconnection** | Kill and restart mock — app should reconnect, wait for IDR, resume cleanly |
| **Touch forwarding** | Touch events appear in mock bridge console output |
| **Settings UI** | Bridge IP, codec display, all tabs functional |
| **Stats overlay** | FPS counter, codec info, frame counts all update |
| **Media metadata** | Track title/artist/album cycling every 15s with album art |
| **Navigation state** | Maneuver cycling every 10s with colored nav images |
| **Cluster display** | Nav maneuvers forwarded to instrument cluster |

### What You Can't Test (Requires Real SBC + Phone)

- Actual AA phone session (Bluetooth, WiFi AP, aasdk)
- Real H.265/VP9 codec negotiation
- HFP call audio (B2)
- GNSS data from a real car (emulator GPS is simulated)
- Hardware video decoder behavior (Qualcomm C2)
- Real car VHAL properties that are permission-denied on Blazer EV (steering, lights, doors, HVAC)

### Setup

1. **Install ffmpeg in WSL** (one-time):
   ```bash
   wsl -d Ubuntu-24.04 -- sudo apt-get install -y ffmpeg
   ```

2. **Start the mock bridge** (from PowerShell):
   ```powershell
   # Default: 1920x1080 @ 30fps, stereo audio
   scripts\start-mock-bridge.ps1

   # Match car display resolution:
   scripts\start-mock-bridge.ps1 -Width 2628 -Height 800 -Fps 60

   # Video only (no audio):
   scripts\start-mock-bridge.ps1 -NoAudio
   ```

3. **Start the AAOS emulator and configure networking**:
   ```powershell
   # The mock bridge listens on localhost. Use adb reverse so the
   # emulator app can reach it:
   adb reverse tcp:5288 tcp:5288
   adb reverse tcp:5289 tcp:5289
   adb reverse tcp:5290 tcp:5290
   ```

4. **Configure app settings**: Set bridge IP to `127.0.0.1` (or `10.0.2.2` without adb reverse).

5. **Launch the app**: Video test pattern should appear, audio should play.

### Mock Bridge Console Output

The mock bridge logs all events to the terminal:

```
==================================================
  OpenAutoLink Mock Bridge
==================================================
  Control: 0.0.0.0:5288
  Video:   0.0.0.0:5290 (1920x1080 @ 30fps)
  Audio:   0.0.0.0:5289 (48kHz stereo)
  Source:  ffmpeg test pattern
==================================================
  Waiting for app connection...

[control] App connected from ('127.0.0.1', 54321)
[control] App hello: OpenAutoLink App 2628x800
[control] Simulated phone connection
[video] App connected from ('127.0.0.1', 54322)
[video] Sent codec config (42 bytes)
[video] Sent IDR frame #1 (8432 bytes)
[video] 150 frames, 30.0 fps
[audio] App connected from ('127.0.0.1', 54323)
```

### Using a Captured H.264 File

You can replay real video captured from the bridge instead of the test pattern:

```powershell
# Capture from the SBC (while a phone session is active):
ssh khadas@192.168.137.x "timeout 10 tcpdump -i eth0 port 5290 -w -" > capture.raw

# Play it back through the mock bridge:
scripts\start-mock-bridge.ps1 -VideoFile capture.h264
```

## 9. Testing in the Real Car

In-car testing is harder than emulator testing but critical at key milestones — it's the only way to validate real hardware decoders, audio routing, VHAL integration, and actual reconnection behavior.

### What's Different

- **No ADB access** — GM locks down ADB on production head units. No `logcat`, no `adb install`, no `adb reverse`
- **No debug APKs** — the app must be built as a **signed AAB** (Android App Bundle) and uploaded to the **Google Play Console internal/closed testing track** for every new build
- **No live debugging** — rely on the app's built-in diagnostics screen for status and error info

### Deploying a Test Build

```powershell
# 1. Build signed AAB (prompts for keystore password)
.\scripts\bundle-release.ps1

# 2. Upload to Play Console
#    Play Console → OpenAutoLink → Testing → Internal testing → Create new release
#    Upload: app/build/outputs/bundle/release/app-release.aab
#    Roll out to internal testers

# 3. On the head unit: open Play Store → update the app
#    (or wait for auto-update if already installed)
```

> **Turnaround time:** Play Console internal testing typically processes builds in minutes, but there's no instant deploy path. Plan testing sessions accordingly.
>
> **Keystore:** The signing keystore lives at `secrets/upload-key.jks` (gitignored). Create one with `scripts\create-upload-keystore.ps1` if you don't have it yet.

### What to Test In-Car (vs Emulator)

| Area | Why in-car matters |
|------|-------------------|
| **Hardware video decoding** | Real Qualcomm C2 decoders behave differently than emulator software decoders |
| **Audio routing** | Car audio system, steering wheel controls, multi-zone output |
| **Reconnection** | Real power cycle — ignition off/on |
| **Touch calibration** | Real touchscreen DPI, coordinate mapping on 2914×1134 display |
| **VHAL data** | Real vehicle speed, gear, parking state |
| **Network stability** | Real WiFi behavior on the car's head unit |

This is why emulator testing handles the bulk of development — it's the only environment with full `adb` access for rapid iteration. In-car testing validates what the emulator can't.

## 10. Remote Diagnostics (No ADB Required)

Since GM locks ADB on the production head unit, the app has built-in remote diagnostics tools that work over TCP — no USB cable, no ADB, no root. Everything runs over the phone's hotspot network (phone + car + laptop all connected).

### Remote Log Server

The app can stream its entire log output over TCP to your laptop in real-time. This replaces `adb logcat` when ADB is unavailable.

#### Setup

1. On the car head unit: **OpenAutoLink → Settings → Diagnostics → Open Diagnostics Dashboard**
2. Go to the **Debug** tab
3. Tap **Start Log Server** — status shows `Listening on <car-ip>:6555`
4. Note the car's IP (also shown in the **Network** tab under Interfaces)

#### Connect from Laptop

All devices must be on the same network (phone's hotspot).

**PowerShell (Windows — no extra tools):**
```powershell
$client = [System.Net.Sockets.TcpClient]::new("<car-ip>", 6555)
$reader = [System.IO.StreamReader]::new($client.GetStream())
while ($null -ne ($line = $reader.ReadLine())) { Write-Host $line }
```

**ncat (if Nmap is installed):**
```powershell
ncat <car-ip> 6555
```

**WSL / Linux:**
```bash
nc <car-ip> 6555
```

#### What You Get

On connect, the server dumps all **buffered entries** (up to 500 from the ring buffer), then live-tails every new log line:

```
=== OpenAutoLink Remote Log Server ===
=== Connected: Sun Apr 27 14:32:01 PDT 2026 ===
=== Dumping 127 buffered entries ===

14:30:01.234 I/SessionManager: Session state → IDLE
14:30:02.456 I/NearbyTransport: Advertising started
...

=== Live tail (new entries streamed in real-time) ===

14:32:05.789 I/VideoDecoder: First frame decoded (H264, 1920x1080)
14:32:05.801 D/AudioPlayer: Media track started, purpose=1
```

#### Details

- Port: **6555** (TCP)
- Supports **multiple simultaneous clients**
- Logs include the same entries visible in Diagnostics → Logs tab (everything via `OalLog`)
- The server runs while the DiagnosticsViewModel is alive — it stops when you leave the diagnostics screen
- The Debug tab shows connected client count and connection log

### Network Port Scanner

The **Network** tab includes a port scanner that probes all reachable hosts for open services.

#### What It Scans

- **Hosts:** localhost (`127.0.0.1`), all interface IPs, inferred gateways (last octet `.1`), and any manually-entered ping target
- **Ports:** 30+ common services including ADB (5037/5555–5559/7555), HTTP, SSH, VNC, Chrome DevTools, OAL ports, and more
- Each open port shows latency and attempts to read a banner

This is useful for discovering whether ADB TCP might be running on a non-standard port, or what other services the head unit exposes.

### ADB / Debug Probe

The **Debug** tab provides deeper inspection of the device's debug capabilities:

| Feature | What It Does |
|---------|-------------|
| **ADB Port Scan** | Scans localhost for ADB-specific ports (5037, 5555–5559, 7555) with banner detection |
| **Debug Properties** | Runs `getprop` and filters for ADB, USB, debug, and GM-specific system properties |
| **Settings.Global** | Reads `ADB_ENABLED` and `adb_wifi_enabled` from Android settings |
| **Device Identity** | Shows manufacturer, model, SOC, build fingerprint, build type |
| **Developer Settings Launcher** | Probes 9 different intents for developer/settings activities and shows which exist on the device. Available intents get a **Launch** button |

#### Developer Settings Intents Probed

| Intent | Purpose |
|--------|---------|
| Android Developer Options | Standard `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` |
| Developer Options (component) | Direct `DevelopmentSettings` activity |
| Dev Options (SubSettings) | Fragment-based `DevelopmentSettingsDashboardFragment` |
| GM Developer Settings | `com.gm.settings.DeveloperSettingsActivity` |
| GM System Settings | `com.gm.settings.SystemSettingsActivity` |
| Android Settings (main) | General `ACTION_SETTINGS` |
| About (tap build number 7x) | `ACTION_DEVICE_INFO_SETTINGS` — navigate here to enable developer mode |
| Wireless Debugging | `WIRELESS_DEBUGGING_SETTINGS` (Android 11+) |
| USB Preferences | `UsbDetailsActivity` — USB debugging configuration |

> **Tip:** If the OEM's developer settings only show USB debugging, try the "About" intent to navigate to the build number — tapping it 7 times enables Android developer mode on most devices.

### Finding the Car's IP

If you don't know the car's IP on the hotspot:

```powershell
# From your laptop — scan the hotspot subnet
arp -a
```

Or check the app's **Diagnostics → Network** tab — it lists all network interface IPs.

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| App can't find companion | Network isolation or mDNS blocked | Ensure both devices on same WiFi; check firewall rules |
| App shows "Connecting..." forever | Companion not running or wrong network | Verify companion service is started and both devices are on the same network |
| Video renders but no audio | Audio purpose routing mismatch | Check logcat for AudioTrack errors; see [embedded-knowledge.md](embedded-knowledge.md) |
| Emulator is slow / video stutters | x86_64 emulated decoder limitations | Expected — emulator hardware decoding is weaker than the real head unit |
| Companion not discovered | mDNS not working across subnets | Try manual IP entry in app settings, or check UDP/TCP probe ports are open |
