# OpenAutoLink — App

AAOS head unit app. Kotlin/Compose, embeds aasdk C++ via JNI for the Android Auto protocol. Renders video/audio, forwards touch/GNSS/vehicle data.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
.\gradlew :app:assembleDebug      # Debug APK
.\gradlew :app:bundleRelease      # Signed AAB for Play Store
.\gradlew :app:testDebugUnitTest  # Unit tests
```

## Architecture

See [docs/architecture.md](../docs/architecture.md) for the component island plan.

| Island | Package | Purpose |
|--------|---------|---------|
| Transport | `transport/` | aasdk JNI session + TCP companion discovery/transport |
| Video | `video/` | MediaCodec lifecycle, Surface rendering |
| Audio | `audio/` | Multi-purpose AudioTrack (5 slots), mic capture |
| Input | `input/` | Touch, GNSS, VHAL vehicle data, IMU sensors |
| UI | `ui/` | Compose screens — projection, settings, diagnostics |
| Navigation | `navigation/` | Nav state from aasdk, cluster service |
| Session | `session/` | Session orchestrator — connects islands |
| Diagnostics | `diagnostics/` | Logging, telemetry, remote debug tools |

## Diagnostics

The app includes built-in diagnostics accessible from **Settings → Diagnostics → Open Diagnostics Dashboard**:

- **System** — Device info, display metrics, hardware codec list
- **Network** — Session state, interface IPs, ping/TCP probe, port scanner
- **Car** — Live VHAL properties (speed, gear, battery, charge state, etc.)
- **Debug** — Remote log server, ADB port scan, system properties, developer settings launcher
- **Logs** — Ring buffer of all app log entries with severity filtering

### Remote Log Server

Stream logs to your laptop over TCP when ADB is unavailable (e.g., GM head units):

1. Open the **Debug** tab in diagnostics
2. Tap **Start Log Server** (listens on port 6555)
3. From your laptop on the same network: `nc <car-ip> 6555`

See [docs/testing.md](../docs/testing.md#10-remote-diagnostics-no-adb-required) for full details.
