# USB Transport for Android Auto — Implementation Plan

## Overview

Add USB as another transport option alongside the current TCP companion path. When a phone is plugged into the car's USB port, our app performs the AOA v2 (Android Open Accessory) handshake to switch the phone into accessory mode, then feeds the raw USB byte pipe into our existing aasdk JNI transport layer.

```
Phone (USB cable) → Car USB Port
                      ↓
              UsbManager enumerates phone
                      ↓
           Our app requests USB permission (system popup)
                      ↓
           AOA v2 handshake (control transfers 51/52/53)
                      ↓
           Phone re-enumerates as Google Accessory (18D1:2D00)
                      ↓
           Our app opens bulk IN/OUT endpoints
                      ↓
           UsbInputStream / UsbOutputStream
                      ↓
           AasdkTransportPipe → JNI → aasdk C++ → AA session
```

## Why This Works

- The car's USB ports DO enumerate devices (confirmed: USB NIC works)
- `UsbManager` API is available on AAOS (confirmed via our USB scanner in Debug tab)
- Our aasdk transport abstraction (`AasdkTransportPipe`) just needs `InputStream`/`OutputStream`
- Headunit-revived proves this exact AOA approach works for AA on AAOS head units
- No root, no ADB, no special permissions beyond the runtime USB permission popup

## Advantages Over WiFi/Nearby

- No hotspot dependency — just a USB cable
- Lower latency — USB bulk transfers vs WiFi
- More reliable — no WiFi disconnects, no AP isolation
- Simpler setup — plug in, grant permission, done
- Works as fallback when Nearby/WiFi fails

## Reference Implementation

Headunit-revived (`external/headunit-revived/`) has a complete working USB AA implementation. Key files to reference:

| File | Purpose | What to learn |
|------|---------|---------------|
| `UsbDeviceCompat.kt` | Wraps UsbDevice, detects accessory mode | VID/PID constants, `isInAccessoryMode()` check |
| `UsbAccessoryMode.kt` | AOA v2 control transfers | `GET_PROTOCOL(51)`, `SEND_STRING(52)`, `START(53)` sequence |
| `UsbAccessoryConnection.kt` | Opens device, claims interface, finds endpoints | `openDevice()`, `claimInterface()`, bulk endpoint detection |
| `UsbReceiver.kt` | BroadcastReceiver for USB attach/detach/permission | Intent filter pattern, permission PendingIntent |
| `UsbAttachedActivity.kt` | Handles `USB_DEVICE_ATTACHED` intent | Auto-connect on plug-in |
| `AapService.kt` | Orchestrates the full flow | Permission → switch → connect → AA session |

NOTE: These are in `external/headunit-revived/app/build/tmp/kapt3/stubs/` (compiled stubs). The original source is in the headunit-revived repo. Use as reference patterns, not copy-paste.

## Implementation Steps

### Step 1: Manifest & Resources

**`app/src/main/AndroidManifest.xml`** — Add:
```xml
<!-- USB Host feature (optional — don't block install on devices without it) -->
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

Add a `UsbAttachedActivity` with intent filter:
```xml
<activity
    android:name=".transport.usb.UsbAttachedActivity"
    android:exported="true"
    android:enabled="true"
    android:directBootAware="true"
    android:theme="@android:style/Theme.NoDisplay">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/usb_device_filter" />
</activity>
```

**`app/src/main/res/xml/usb_device_filter.xml`** — Create:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Match all USB devices for enumeration -->
    <usb-device />
    <!-- Google Accessory mode devices (after AOA switch) -->
    <usb-device vendor-id="6353" product-id="11520" />
    <usb-device vendor-id="6353" product-id="11521" />
</resources>
```

### Step 2: USB Transport Layer — `app/src/main/java/com/openautolink/app/transport/usb/`

Create these files:

#### `UsbConstants.kt`
AOA protocol constants:
- `ACC_REQ_GET_PROTOCOL = 51`
- `ACC_REQ_SEND_STRING = 52`
- `ACC_REQ_START = 53`
- `ACC_IDX_MAN/MOD/DES/VER/URI/SER = 0..5`
- `GOOGLE_VID = 0x18D1`
- `ACC_PID = 0x2D00`
- `ACC_ADB_PID = 0x2D01`

#### `UsbAccessoryMode.kt`
AOA v2 switch logic:
- `fun switchToAccessory(usbManager: UsbManager, device: UsbDevice): Boolean`
- Opens device connection
- Sends `GET_PROTOCOL` control transfer → verify AOA v1+ support
- Sends 6 `SEND_STRING` control transfers (manufacturer="Android", model="Android Auto", etc.)
- Sends `START` control transfer → phone re-enumerates
- Returns true if switch was initiated successfully

#### `UsbTransportPipe.kt`
Wraps USB bulk endpoints as `InputStream`/`OutputStream`:
```kotlin
class UsbTransportPipe(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) {
    fun toInputStream(): InputStream  // wraps bulkTransfer(endpointIn, ...)
    fun toOutputStream(): OutputStream  // wraps bulkTransfer(endpointOut, ...)
    fun close()
}
```

This feeds directly into `AasdkTransportPipe(inputStream, outputStream)` — no changes needed to the JNI layer.

#### `UsbConnectionManager.kt`
Full lifecycle orchestrator:
- Listens for USB attach/detach via `BroadcastReceiver`
- Manages permission requests via `UsbManager.requestPermission()`
- Detects if device is already in accessory mode
- Performs AOA switch if not
- Opens device, claims interface, finds bulk IN/OUT endpoints
- Creates `UsbTransportPipe` → `AasdkTransportPipe` → feeds to `AasdkSession`
- Handles disconnection and cleanup
- Exposes `StateFlow<UsbConnectionState>` for UI

States:
```
IDLE → DEVICE_DETECTED → PERMISSION_REQUESTED → SWITCHING_TO_ACCESSORY
    → ACCESSORY_DETECTED → CONNECTING → CONNECTED → STREAMING
```

#### `UsbAttachedActivity.kt`
Thin activity that receives `USB_DEVICE_ATTACHED` intent:
- Checks if OpenAutoLink is the active transport handler
- If USB transport is enabled in settings, starts connection flow
- Finishes immediately (no UI)

### Step 3: Settings Integration

Add to `AppPreferences`:
- `transport_mode: String` — "nearby" | "hotspot" | "usb" | "auto"
- When "auto": try USB first if a device is present, fall back to Nearby/hotspot
- When "usb": only use USB transport

Add to Settings → Connection tab:
- Transport mode selector (already exists — add "USB" option)
- USB-specific info: "Plug phone into car USB port, grant permission when prompted"

### Step 4: Session Integration

Modify `AasdkSession` to accept USB transport:
- Currently `AasdkSession.start()` creates `AasdkTransportPipe` from TCP companion, Nearby, or USB streams depending on transport mode
- Add `startUsb(connection: UsbDeviceConnection, endpointIn, endpointOut)` variant
- Creates `UsbTransportPipe` → `AasdkTransportPipe` → same JNI path
- The entire C++ aasdk layer is transport-agnostic — no changes needed there

### Step 5: UI Feedback

- ProjectionScreen: show "USB" transport indicator when connected via USB
- Connection state: "Waiting for USB device..." / "Permission requested..." / "Switching to accessory mode..." / "Connected via USB"
- Settings: show currently connected USB device name and status

## Architecture Diagram

```
┌─────────────────────────────────────────────────┐
│ Phone (Android Auto)                            │
│                                                 │
│  AA App ← system USB accessory driver           │
│           VID=18D1 PID=2D00                     │
└──────────────────┬──────────────────────────────┘
                   │ USB cable
                   │ (bulk IN/OUT)
┌──────────────────┴──────────────────────────────┐
│ Car Head Unit (AAOS)                            │
│                                                 │
│  UsbManager.openDevice()                        │
│       ↓                                         │
│  UsbTransportPipe (InputStream/OutputStream)    │
│       ↓                                         │
│  AasdkTransportPipe ← SAME as Nearby path       │
│       ↓ (JNI)                                   │
│  JniTransport (C++) → aasdk Messenger           │
│       ↓                                         │
│  aasdk channels (video, audio, input, nav...)   │
│       ↓                                         │
│  Existing islands: VideoDecoder, AudioPlayer,   │
│                    TouchForwarder, etc.          │
└─────────────────────────────────────────────────┘
```

## What Does NOT Need to Change

- **C++ JNI layer** — `JniTransport` reads/writes through `AasdkTransportPipe` which is just `InputStream`/`OutputStream`. Transport-agnostic.
- **aasdk library** — doesn't know or care about the underlying transport
- **Video/Audio/Input islands** — receive data from aasdk channels regardless of transport
- **Companion app** — not involved in USB mode at all

## Testing Strategy

1. **Emulator**: Cannot test USB — no physical USB host on emulator
2. **Real car**: Plug phone into USB port that enumerates devices
   - Verify USB device appears in Debug tab USB scanner
   - Verify permission popup appears
   - Verify AOA switch (device re-enumerates as 18D1:2D00)
   - Verify AA session starts and projection works
3. **Fallback**: If USB port doesn't support USB Host mode or AOA, the app gracefully falls back to Nearby/Hotspot

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| GM may block USB Host mode on some ports | Graceful fallback to WiFi. We confirmed USB NIC works on at least one port |
| AOA switch may fail on some phones | Retry with backoff, fall back to WiFi |
| USB permission popup may confuse users | Clear instructions in Settings and connection UI |
| USB cable disconnect during session | Detect via USB detach broadcast, reconnect if re-plugged |
| Concurrent access with stock AA app | Our manifest filter should take priority if we're the foreground activity |

## File Checklist

New files to create:
- [ ] `app/src/main/java/com/openautolink/app/transport/usb/UsbConstants.kt`
- [ ] `app/src/main/java/com/openautolink/app/transport/usb/UsbAccessoryMode.kt`
- [ ] `app/src/main/java/com/openautolink/app/transport/usb/UsbTransportPipe.kt`
- [ ] `app/src/main/java/com/openautolink/app/transport/usb/UsbConnectionManager.kt`
- [ ] `app/src/main/java/com/openautolink/app/transport/usb/UsbAttachedActivity.kt`
- [ ] `app/src/main/res/xml/usb_device_filter.xml`

Files to modify:
- [ ] `app/src/main/AndroidManifest.xml` — USB feature + UsbAttachedActivity
- [ ] `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt` — add `startUsb()` variant
- [ ] `app/src/main/java/com/openautolink/app/data/AppPreferences.kt` — add USB transport option
- [ ] `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt` — USB option in connection tab
- [ ] `app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt` — USB connection state
- [ ] `app/src/main/java/com/openautolink/app/session/SessionManager.kt` — orchestrate USB transport
