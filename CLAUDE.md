# OpenAutoLink

Android Auto head-unit app for AAOS (Android Automotive OS) and non-automotive Android devices. Connects to a phone running the OpenAutoLink companion app over WiFi (Car Hotspot or shared network) and projects Android Auto onto the car's display.

## Architecture

Two APKs in one repo:

- **`app/`** — Head-unit app running on the car's AAOS head unit. Handles AA protocol via aasdk JNI, video decode, audio playback, touch input, VHAL sensor forwarding, cluster navigation.
- **`companion/`** — Phone-side app that bridges the AA protocol over TCP to the head unit. Manages WiFi connectivity, TCP server, syslog forwarding.

### Data flow

```
Phone AA app ↔ Companion (TCP server) ↔ WiFi ↔ App (TcpConnector) → aasdk JNI C++ → Kotlin flows
  → VideoDecoder (MediaCodec) / AudioPlayer (AudioTrack) / SessionManager (control messages)
```

### Key components

| Component | File | Role |
|-----------|------|------|
| `SessionManager` | `app/.../session/SessionManager.kt` | Top-level orchestrator — wires component islands together, manages lifecycle |
| `AasdkSession` | `app/.../transport/aasdk/AasdkSession.kt` | AA protocol session — owns TCP transport, exposes Kotlin flows for decoded frames |
| `MediaCodecDecoder` | `app/.../video/MediaCodecDecoder.kt` | H.264/H.265/VP9 video decoder via MediaCodec |
| `AudioPlayerImpl` | `app/.../audio/AudioPlayerImpl.kt` | 5-purpose AudioTrack management with ducking |
| `VehicleDataForwarderImpl` | `app/.../input/VehicleDataForwarderImpl.kt` | VHAL property monitoring via AAOS Car API reflection |
| `TcpConnector` | `app/.../transport/hotspot/TcpConnector.kt` | TCP client with mDNS + gateway discovery |
| `AaProxy` | `companion/.../connection/AaProxy.kt` | Companion-side TCP server + AA protocol bridge |

## Key patterns

- **Flow-based reactive architecture** — all decoded outputs (video frames, audio frames, control messages) are `SharedFlow`s consumed by `SessionManager` via `collect` in dedicated coroutine scopes.
- **Edge-triggered VHAL forwarding** — low-cadence properties (night mode, parking brake, gear) are only forwarded to the phone when their value changes, not on every tick.
- **Generation counter for stale callback safety** — `AasdkSession` uses a `sessionGeneration` counter to prevent stale JNI callbacks from racing with freshly-started sessions.
- **Delegate pattern for cluster sessions** — `ClusterSessionDelegate` holds shared navigation state collection + retry logic used by both `ClusterMainSession` (GM) and `OalClusterSession` (standard AAOS).

## Hot paths

- **Video decode** — `MediaCodecDecoder.onFrame()` → `queueFrame()` → `MediaCodec` input → drain thread → Surface render. The drain thread runs at `MAX_PRIORITY`.
- **Audio playback** — `AudioPlayerImpl.onAudioFrame()` → per-purpose `AudioPurposeSlot.feedPcm()` → dedicated single-thread executor → `AudioTrack.write()`. Each purpose has its own write thread so one stalling doesn't block others.
- **NAL parsing** — `NalParser.findStartCode()` is called for every NAL unit in every video frame. Optimized to skip non-zero bytes and single-zero bytes.

## Diagnostics

- `DiagnosticLog` — structured log with levels (DEBUG/INFO/WARN/ERROR), tag-based filtering, remote viewing via `RemoteDiagnosticsImpl`
- `TelemetryCollector` — periodic stats snapshot (FPS, bitrate, audio underruns, decoder state)
- `TcpSyslogSink` (companion) — streams logs from head unit to a remote syslog server over TCP

## Build

Standard Android Gradle build with two modules (`app` and `companion`). The aasdk C++ library is pre-built and linked via JNI.
