# OpenAutoLink Architecture

Last reviewed against code: May 3, 2026.

OpenAutoLink is currently a bridgeless AAOS projection stack. The AAOS app runs the Android Auto head-unit protocol through aasdk C++ in-process via JNI. The phone companion app does not decode or interpret Android Auto; it starts a local Android Auto wireless session on the phone and relays that byte stream to the car app.

The older SBC bridge and three-channel OAL protocol are not part of the active branch. See [protocol.md](protocol.md) only when working on historical bridge-mode code.

## Current Topology

```
Android phone                                      AAOS head unit
┌─────────────────────────────────────────┐        ┌──────────────────────────────────────┐
│ Google Android Auto / Gearhead          │        │ OpenAutoLink AAOS app                 │
│   connects to 127.0.0.1:<ephemeral>     │        │                                      │
│                 │                       │        │ PhoneDiscovery + TcpConnector         │
│                 ▼                       │        │   finds and dials phone:5277          │
│ OpenAutoLink companion                  │        │                 │                    │
│   CompanionService foreground service   │        │                 ▼                    │
│   TcpAdvertiser :5277                   │◄──────►│ AasdkTransportPipe                    │
│   Identity TCP :5278                    │        │                 │                    │
│   UDP discovery :5279                   │        │                 ▼                    │
│   mDNS _openautolink._tcp               │        │ JNI JniTransport + JniSession         │
│   AaProxy localhost bridge              │        │                 │                    │
└─────────────────────────────────────────┘        │ aasdk control/video/audio/etc.        │
                                                   │                 │                    │
                                                   │ SessionManager routes frames/events   │
                                                   └──────────────────────────────────────┘
```

The TCP socket between the apps is a single bidirectional AA byte pipe. Video, audio, touch, mic, navigation, media status, phone status, GNSS, IMU, and VHAL sensor data are multiplexed by the Android Auto protocol inside aasdk, not by OpenAutoLink JSON or custom media framing.

## Responsibilities

### AAOS App (`app/`)

| Area | Current code | Responsibility |
|------|--------------|----------------|
| Discovery | `transport/PhoneDiscovery.kt`, `transport/direct/TcpConnector.kt` | Finds a running companion with mDNS, UDP broadcast, TCP identity probes, gateway fallback, or manual IP. |
| AA transport | `transport/aasdk/AasdkSession.kt`, `AasdkTransportPipe.kt` | Wraps a connected `Socket` or USB pipe as blocking streams for native aasdk. |
| AA protocol | `app/src/main/cpp/jni_session.*`, `jni_channel_handlers.*`, `jni_transport.*` | Version exchange, TLS/auth, service discovery response, heartbeat, channel handling, JNI callbacks. |
| Session orchestration | `session/SessionManager.kt` | Connects transport output to video, audio, input, navigation, diagnostics, VHAL, GNSS, and IMU islands. |
| Media | `video/`, `audio/` | Decodes projected video, plays per-purpose audio, captures mic audio when requested. |
| Vehicle/input forwarding | `input/` | Sends touch, steering wheel, GNSS, IMU, and VHAL sensor data to the phone through aasdk. |

`AasdkSession.transportMode` supports `"hotspot"`, `"nearby"`, and `"usb"` in code, but the app/companion workflow reviewed here is the TCP/hotspot path. Nearby transport code remains in the tree, while the phone companion UI currently forces stale Nearby preferences back to TCP.

### Phone Companion (`companion/`)

| Area | Current code | Responsibility |
|------|--------------|----------------|
| Service lifecycle | `service/CompanionService.kt` | Foreground service, notification, wake lock, multicast lock, TCP advertiser startup, optional car WiFi helper. |
| TCP listener | `service/TcpAdvertiser.kt` | Listens on `0.0.0.0:5277`, publishes mDNS, answers identity probes, launches Android Auto. |
| Discovery identity | `CompanionPrefs`, `TcpAdvertiser` | Publishes stable `phone_id`, user-friendly name, and protocol version via mDNS TXT and identity responses. |
| Local AA proxy | `connection/AaProxy.kt` | Accepts Google AA on localhost and pumps bytes between the local AA socket and the car socket. |
| Car WiFi assist | `wifi/CarWifiManager.kt` | Optionally requests the car hotspot with `WifiNetworkSpecifier`; TCP remains bound to all interfaces. |
| Auto-start | `autostart/` | Starts the foreground service from configured Bluetooth, WiFi, or app-open triggers. |

The companion is deliberately dumb about Android Auto. It does not parse AA frames, make routing decisions for media channels, or inspect projected content.

## Discovery Pipeline

The car app prefers fresh discovery over cached IPs because car hotspots may hand out different subnets across boots.

1. `PhoneDiscovery.start()` begins Android NSD discovery for `_openautolink._tcp`.
2. On Android 14+, the app uses `NsdManager.registerServiceInfoCallback` to see all resolved addresses and prefer IPv4. Older versions fall back to `resolveService`.
3. If mDNS is slow or returns only unusable IPv6 link-local addresses, `udpBroadcastAllInterfaces()` sends `OAL?\n` to each usable `/24` broadcast address on UDP `5279`.
4. If broadcast fails, `startSweep()` probes each host in relevant `/24` networks on TCP `5278`.
5. A companion answers identity probes with `OAL!{phone_id}\t{friendly_name}\n`.
6. The selected phone host is passed to `TcpConnector`, which dials `host:5277`.

Manual IP mode bypasses discovery and makes `TcpConnector` retry only that host. It is useful for emulator or bench testing, but stale manual IPs can block fallback discovery.

```mermaid
flowchart TD
    A[ProjectionViewModel.connect] --> B{Car Hotspot mode?}
    B -- no --> C[Start configured transport]
    B -- yes --> D[PhoneDiscovery.start mDNS]
    D --> E{resolved phone found during grace window?}
    E -- yes --> I[Use discovered host]
    E -- no --> F[UDP OAL? broadcast on 5279]
    F --> G{reply received?}
    G -- yes --> I
    G -- no --> H[TCP /24 sweep to 5278]
    H --> I
    I --> J[TcpConnector connects to 5277]
    J --> K[AasdkSession starts native session]
```

This mirrors the Android NSD model: the platform discovers DNS-SD services with `discoverServices()` and resolves host/port details before connecting. See the official Android NSD guide: <https://developer.android.com/develop/connectivity/wifi/use-nsd>.

## App/Companion Handshake

There are two layers of handshake:

1. OpenAutoLink discovery and socket setup between the AAOS app and companion.
2. Android Auto protocol handshake between native aasdk on the AAOS app and Google Android Auto on the phone, carried through the companion proxy.

```mermaid
sequenceDiagram
    participant CarUI as AAOS ProjectionViewModel
    participant Disc as PhoneDiscovery
    participant Tcp as TcpConnector
    participant Comp as Companion TcpAdvertiser
    participant Proxy as Companion AaProxy
    participant AA as Google Android Auto
    participant JNI as AasdkSession/JniSession

    CarUI->>Disc: start mDNS discovery
    Comp-->>Disc: mDNS _openautolink._tcp TXT phone_id/name
    alt mDNS missing or unusable
        CarUI->>Disc: UDP OAL? broadcast :5279
        Disc-->>Comp: OAL?\n
        Comp-->>Disc: OAL!phone_id\tname\n
    else broadcast blocked
        CarUI->>Disc: TCP identity probe sweep :5278
        Disc->>Comp: OAL?\n
        Comp-->>Disc: OAL!phone_id\tname\n
    end
    CarUI->>Tcp: connect selected host:5277
    Tcp->>Comp: TCP connect
    Comp->>Proxy: create one-shot localhost proxy
    Proxy-->>Comp: listen 127.0.0.1:ephemeral
    Comp->>AA: launch WirelessStartupActivity(host=127.0.0.1, port=ephemeral)
    AA->>Proxy: TCP connect localhost
    Proxy<<->>JNI: raw AA bytes over car TCP socket
    JNI->>AA: VersionRequest 1.7
    AA-->>JNI: VersionResponse
    JNI<<->>AA: TLS handshake frames
    JNI->>AA: AuthComplete
    AA->>JNI: ServiceDiscoveryRequest
    JNI-->>AA: ServiceDiscoveryResponse with channels and ping config
    JNI->>JNI: start channel handlers
    JNI-->>CarUI: onSessionStarted / STREAMING
```

Important implementation details:

- `TcpAdvertiser` never reads from the `5277` AA stream before handing it to `AaProxy`; identity probing is isolated on `5278`.
- `AaProxy` is one-shot. If Android Auto reconnects, the companion rejects extra local AA sockets and the car establishes a fresh remote TCP session.
- The companion launch watchdog waits 8 seconds for Google AA to connect to the local proxy, retries the launch intent up to 3 times, then closes the car socket to force a clean reconnect.
- Native `JniSession` has a 15 second AA handshake watchdog. If streaming does not start, it aborts the transport so Kotlin reconnect logic can rebuild state.

## Heartbeat And Liveness

Three liveness mechanisms exist at different layers:

| Layer | Code | Behavior |
|-------|------|----------|
| Android Auto ping | `jni_session.cpp` | The service discovery response advertises `interval_ms=1500`, `timeout_ms=5000`, `tracked_ping_count=5`, `high_latency_threshold_ms=500`. After service handlers start, `JniSession` sends an initial ping and schedules pings every 1500 ms. |
| AA ping watchdog | `jni_session.cpp` | If a ping is outstanding for more than 8000 ms, `triggerAbort("ping timeout ...")` stops the transport and emits `onSessionStopped`. |
| TCP keepalive | `TcpConnector.kt` | Enables `SO_KEEPALIVE` and best-effort Linux TCP keepalive options: idle 5 s, interval 2 s, count 3, so ungraceful phone loss is noticed in roughly 10 s when supported. |

The phone may also send AA ping requests. `JniSession.onPingRequest()` echoes the timestamp in a `PingResponse`.

```mermaid
sequenceDiagram
    participant JNI as AAOS JniSession
    participant Proxy as Companion AaProxy
    participant AA as Google Android Auto
    participant Kotlin as AasdkSession

    JNI->>AA: PingRequest(timestamp)
    AA-->>JNI: PingResponse(timestamp)
    JNI->>JNI: pingOutstanding=false
    loop every 1500 ms
        alt no outstanding ping
            JNI->>AA: PingRequest(timestamp)
        else outstanding ping age <= 8000 ms
            JNI->>JNI: wait for response
        else outstanding ping age > 8000 ms
            JNI->>Proxy: stop transport
            JNI-->>Kotlin: onSessionStopped("ping timeout ...")
            Kotlin->>Kotlin: schedule reconnect with backoff
        end
    end
```

There is no legacy heartbeat-gated media write path in the active architecture. TCP flow control handles the app/companion byte pipe, and AA media acknowledgements are protocol-level channel flow control inside aasdk.

## Session State

`AasdkSession.connectionState` is intentionally coarse:

```
DISCONNECTED -> CONNECTING -> CONNECTED
```

`SessionManager` maps that to UI-facing states. `AasdkSession.onSessionStarted()` sets `CONNECTED` and emits `ControlMessage.PhoneConnected`; `SessionManager.handleControlMessage()` promotes the session to streaming when the phone-connected event arrives. Video and audio then flow through independent coroutines and dedicated dispatchers.

Unexpected stops go through `AasdkSession.onSessionStopped(reason)`:

- close the current `AasdkTransportPipe`
- emit `ControlMessage.PhoneDisconnected`
- if the user did not explicitly stop, restart the selected transport after backoff
- use longer backoff after aasdk protocol/handshake error 30, because the phone may still be tearing down an old TLS session

Sleep/wake recovery uses `SessionManager.forceReconnect()` paths and screen receiver state to avoid keeping stale sockets alive across AAOS suspend.

## Component Islands

The current islands still follow the original independence rule: public flows and callbacks are wired by `SessionManager`, not by cross-package shortcuts.

| Island | Package | Notes |
|--------|---------|-------|
| Transport | `com.openautolink.app.transport` | Discovery, TCP connector, aasdk session, optional USB/Nearby code. |
| Video | `com.openautolink.app.video` | `MediaCodecDecoder` consumes `VideoFrame` callbacks from aasdk. It waits for codec config/keyframes and can request IDR. |
| Audio | `com.openautolink.app.audio` | Per-purpose `AudioTrack` playback, ring buffers, mic capture. |
| Input | `com.openautolink.app.input` | Touch scaling, steering wheel mapping, GNSS, VHAL, IMU, EV energy model sensors. |
| Navigation | `com.openautolink.app.navigation` | Maneuver state, icon rendering, cluster integration. |
| UI | `com.openautolink.app.ui` | Compose projection, settings, diagnostics, phone chooser. |
| Diagnostics | `com.openautolink.app.diagnostics` | In-app logs, telemetry, remote log server for no-ADB head units. |

## External API Notes

- Android NSD is the supported platform mechanism for local DNS-SD discovery and resolution. OpenAutoLink uses `_openautolink._tcp` plus TXT attributes for stable phone identity. Official docs: <https://developer.android.com/develop/connectivity/wifi/use-nsd>.
- The companion uses a foreground service because keeping the phone-side listener and proxy alive is user-visible connection work. Android foreground services are required to show a notification. Official docs: <https://developer.android.com/develop/background-work/services/fgs>.
- `CarWifiManager` uses `WifiNetworkSpecifier`, which Android documents as the API for requesting a Wi-Fi network and, on supported devices, local-only concurrent connections. Official docs: <https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier>.
- Nearby Connections remains in code as a legacy/alternate transport. Google documents stream payload handling and peer-to-peer strategies, including `P2P_POINT_TO_POINT`, but the companion currently forces its UI transport mode to TCP. Official docs: <https://developers.google.com/nearby/connections/android/exchange-data> and <https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy>.
