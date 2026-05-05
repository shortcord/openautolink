# Native Wireless Mode

Last reviewed against code: May 5, 2026.

`Native Wireless` is an experimental no-companion Android Auto mode. Instead of asking the phone-side companion app to proxy Android Auto over localhost, the AAOS app itself tries to behave like a wireless Android Auto head unit:

1. Create a WiFi Direct group on the AAOS device.
2. Publish the group's SSID, PSK, BSSID, and TCP port over the Android Auto Bluetooth RFCOMM bootstrap UUID.
3. Advertise `_aawireless._tcp` on port `5288`.
4. Advertise the AA wireless support back to the phone through native `aasdk` service channels (`BluetoothService` + `WifiProjectionService`).
5. Accept the phone's direct Android Auto socket in `AasdkSession`.
6. Hand that socket to native `aasdk` for the usual AA protocol handling.

## Code Paths

| Area | File | Responsibility |
|------|------|----------------|
| Session transport | `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt` | Adds `transportMode="native"`, boots the native wireless listeners, feeds WiFi Direct credentials into `AasdkSdrConfig`, and passes the accepted socket into `aasdk`. |
| Bluetooth bootstrap | `app/src/main/java/com/openautolink/app/transport/direct/AaBtHandshakeManager.kt` | Exposes the Android Auto RFCOMM service UUID, returns WiFi credentials, and tells the phone where to dial TCP. |
| WiFi Direct | `app/src/main/java/com/openautolink/app/transport/direct/AaWifiDirectManager.kt` | Creates a P2P group and reports SSID / PSK / BSSID / group-owner IP. |
| aasdk wireless services | `app/src/main/cpp/jni_session.cpp`, `jni_channel_handlers.cpp` | Advertises `BluetoothService` and `WifiProjectionService` in SDR and answers WiFi credential requests inside the native aasdk session. |
| Settings UI | `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt` | Exposes the experimental transport toggle and bootstrap status. |

## Expected Startup Sequence

1. User selects `Native Wireless` in Settings.
2. OpenAutoLink starts WiFi Direct group creation.
3. Once group credentials and the WiFi Direct group-owner IP are ready, OpenAutoLink starts listening on the Android Auto Bluetooth RFCOMM UUID.
4. OpenAutoLink starts a TCP listener on port `5288` and registers `_aawireless._tcp`.
5. When the phone reaches the AA protocol stage, native `aasdk` advertises `BluetoothService` and `WifiProjectionService` in SDR and can answer WiFi credential requests through the AA service channels.
6. The paired phone should recognize the car as a wireless Android Auto head unit, connect over Bluetooth, receive WiFi credentials, join the WiFi Direct group, then open the AA socket to `:5288`.
7. `AasdkSession` starts the native AA session on that accepted socket.

## Preconditions For Testing

1. Phone is paired to the car over Bluetooth.
2. Stock Android Auto is installed and enabled on the phone.
3. AAOS build supports Bluetooth RFCOMM server sockets on the Android Auto UUID.
4. AAOS build supports WiFi Direct group creation and exposes the group IP on a usable `p2p*` interface.

## Current Limitations

1. This path is experimental and has not been validated end-to-end on the real car yet.
2. Behavior may vary across phone vendors and Android versions.
3. WiFi Direct support on AAOS is platform-sensitive; group creation may fail or stall on some builds.
4. The phone's decision to start wireless Android Auto is still controlled by Google's Android Auto app. OpenAutoLink can only emulate the expected head-unit-side bootstrap.
5. Current status reporting distinguishes WiFi Direct setup from Bluetooth bootstrap; if WiFi Direct never reaches `ready`, the Bluetooth stage is not expected to proceed.

## On-Car Test Checklist

1. Pair the phone to the car over Bluetooth first.
2. In OpenAutoLink, select `Native Wireless` and tap `Save & Reconnect`.
3. Watch logs for:
    - `WiFi Direct ready`
    - `Listening on AA UUID`
    - `Native wireless NSD service registered`
    - `Phone connected via BT`
    - `Bluetooth pairing request`
    - `Sending Bluetooth authentication data`
    - `WiFi projection channel open`
    - `WiFi credentials request`
    - `Sent WifiStartRequest`
    - `Native wireless phone connected`
    - `Starting native aasdk session`
4. Verify first frame, audio, touch, cluster nav, and reconnect after screen off/on.

## Fallback Guidance

If the phone never initiates the direct TCP connection after Bluetooth pairing, the missing piece is almost certainly in the wireless Android Auto bootstrap, not in `aasdk`. In that case, fall back to `Car Hotspot`, `Phone Hotspot`, or `USB` and compare logs.
