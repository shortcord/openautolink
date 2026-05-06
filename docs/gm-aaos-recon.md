# GM AAOS APK Recon — Findings for OpenAutoLink

This document captures concrete, code-level findings from decompiling the GM
AAOS firmware APKs (Chevy Blazer EV, Android 12 / SDK 32, GM build
`VCUSR5-163/239`). Source dump and triage notes live in the gitignored
`recon_dump/` directory; this file is the durable summary of what we
learned and how it shaped the project.

> **Scope.** Only the highest-value APKs for OpenAutoLink were analysed in
> depth: `com_gm_aapclient` (32 MB) and `com_gm_hmi_androidauto` (45 MB).
> Other APKs in `recon_dump/apks/` (Ultifi services, EV energy model,
> trusted device, etc.) are catalogued but not deeply examined here.

## TL;DR

GM does **not** implement Android Auto from scratch — they ship Google's
`com.google.android.projection.*` library and wrap it in a thin GM service
layer (`gm.gal`, `gm.connection`, `com.gm.server.gal`). The interesting
parts for us are:

1. **Wire-level service IDs and audio config** — confirmed against our
   own implementation.
2. **The Service Discovery Response (SDR) `VideoConfiguration` fields they
   actually populate** — this is what the phone-side AA module honours,
   and several of our previous defaults were wrong.
3. **The aspect-ratio strategy** — they use `width_margin` /
   `height_margin` to letterbox a 16:9 codec frame inside a non-16:9 panel
   rect, and let the car compositor uniformly scale the inner content rect
   onto the panel. They do *not* use `pixel_aspect_ratio_e4` for AR
   compensation (it's hard-coded to `10000` = 1.0).
4. **Rectangular `content_insets` only.** The AA protocol has no
   non-rectangular safe-area / mask. Curved corners must be approximated
   by the largest rectangle that fits inside the curve.

These findings are the basis of the
[pixel-aspect.instructions.md](../.github/instructions/pixel-aspect.instructions.md)
rules, the auto-margin/margin-zoom renderer in
[ProjectionScreen.kt](../app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt),
the per-tier margin computation in
[jni_session.cpp](../app/src/main/cpp/jni_session.cpp), and the GM-matching
SDR defaults in
[AasdkSdrConfig.kt](../app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSdrConfig.kt).

## 1. AA service IDs

From `com.p087gm.server.gal.service.internals.utils.AAServiceID`:

| ID | Service |
|---:|---|
| 1 | DISPLAY (video) |
| 2 | INPUT (touch + key) |
| 3 | SENSOR |
| 4 | AUDIO_MEDIA_PCM |
| 5 | AUDIO_MEDIA_AAC |
| 6 | AUDIO_TTS_PCM |
| 7 | AUDIO_TTS_AAC |
| 8 | AUDIO_SOURCE (mic) |
| 9 | BLUETOOTH |
| 10 | MEDIA_PLAYBACK_STATUS |
| 11 | MEDIA_BROWSE |
| 12 | PHONE_STATUS |
| 16 | WIFI_PROJECTION |
| 20 | HOTWORD_VENDOR_EXTENSION |
| 21 | GAL_VENDOR_EXTENSION |
| 30 | NAVIGATION_STATUS |
| 31 | DISPLAY_AUXILIARY |
| 32 | DISPLAY_AUXILIARY_INPUT |
| 33 | DISPLAY_CLUSTER |
| 34 | DISPLAY_CLUSTER_INPUT |

Our [`jni_channel_handlers.cpp`](../app/src/main/cpp/jni_channel_handlers.cpp)
matches IDs 1–11 and 30/33; we don't currently implement 16 (WiFi
projection control), 20/21 (vendor extensions), or 31/32 (auxiliary
display).

## 2. Wireless transport: TCP server on port 5277

From `GALManager.startTcpServerMode()`:

```java
TransportFactory factory = TransportFactory.builder()
    .setContext(context)
    .setServerSocketProvider(serverSocketProvider)
    .build();
Log.i(TAG, "Starting TCP server mode on port 30515");
factory.open(null, new WireLessTransportFactoryCallback(sessionId));
```

(The `30515` constant in the log is an internal mDNS service port; the
actual AA TCP listener is `5277`, the well-known wireless AA port — confirmed
by the WiFiInfoResponse handler in our companion's `TcpAdvertiser`.)

Notable preconditions:

- The car requires a **valid BT MAC** (`BluetoothProxy.getHostAddress()`)
  before opening the TCP server. The MAC is sent in the AA wireless
  bootstrap so the phone knows which device to dial.
- The car is the TCP **server**; the phone dials in. Our companion app
  inverts this in Car-Hotspot mode (companion is the server; the car app
  dials the companion). Both modes match this pattern at the AA layer —
  what differs is which endpoint runs the TCP listener.

## 3. Audio configuration — only 4 streams, not 5

From `GALManager.registerAudioSinkService`:

```java
mMediaStreamPCMPlayer  = new AudioPlayer("MEDIA CHANNEL_PCM",     AUDIO_STREAM_MEDIA,    48000, 12, 2, ...);
mTtsStreamPCMPlayer    = new AudioPlayer("GUIDANCE CHANNEL_PCM",  AUDIO_STREAM_GUIDANCE, 48000, 4,  2, ...);
mMediaStreamAACPlayer  = new AudioPlayer("MEDIA CHANNEL_AAC",     AUDIO_STREAM_MEDIA,    48000, 12, 2, ...);
mTtsStreamAACPlayer    = new AudioPlayer("GUIDANCE CHANNEL_AAC",  AUDIO_STREAM_GUIDANCE, 48000, 4,  2, ...);
```

- GM uses only **2 logical streams**: `MEDIA` and `GUIDANCE`. There is no
  separate ALERT/SYSTEM/SIRI purpose — the phone routes those through one
  of the two.
- All sinks are **48 kHz / 16-bit**. Media is stereo (channel mask `12` =
  `CHANNEL_OUT_FRONT_LEFT | FRONT_RIGHT`); guidance is mono (mask `4` =
  `FRONT_CENTER`).
- **PCM and AAC are separate AAServiceIDs** (4/5 and 6/7). GM advertises
  both, then negotiates per-session. This explains why the phone sometimes
  flips codecs mid-session — same logical stream, different codec.

We have a more granular 5-purpose model in [audio/](../app/src/main/java/com/openautolink/app/audio).
This is fine — extra purposes simply receive nothing if the phone never
emits them — but allocating empty `AudioTrack`s is small wasted work. Not
worth changing unless we see the wasted allocation in a profiler.

## 4. SDR `VideoConfiguration` — the GM defaults

From `GALDisplayManager.buildVC` (lightly reformatted):

```java
VideoConfiguration.newBuilder()
    .setCodecResolution(VIDEO_1920x1080 | VIDEO_1280x720 | VIDEO_800x480)
    .setFrameRate(VIDEO_FPS_60)
    .setWidthMargin(wm)              // pixels of black bar L+R inside the codec frame
    .setHeightMargin(hm)             // pixels of black bar T+B inside the codec frame
    .setDensity(scaledDensity)       // logical DPI (sized so UI elements end up correct)
    .setRealDensity(realDensity)     // physical DPI for fonts
    .setDecoderAdditionalDepth(1)    // extra reordered frames the HU can buffer
    .setViewingDistance(700)         // mm from driver eye to display
    .setPixelAspectRatioE4(10000)    // = 1.0, ALWAYS. Phone ignores non-1.0.
    .setUiConfig(uiConfig);          // content_insets, stable_content_insets, margins
```

GM advertises only **`VIDEO_1920x1080`, `VIDEO_1280x720`, `VIDEO_800x480`**
(in that order) regardless of trim. The full `VideoCodecResolutionType`
enum supports up to `VIDEO_3840x2160` plus portrait mirrors
(`VIDEO_720x1280` … `VIDEO_2160x3840`); GM's choice to omit them keeps the
session bandwidth/CPU budget tight on retail trims.

### What the phone actually honours

- `width_margin` / `height_margin` — yes, phone respects these and packs
  margin pixels at the **bottom and right** (UI top-left anchored inside
  the inner content rect).
- `density` and `real_density` — yes, used for UI sizing.
- `decoder_additional_depth` — yes; impacts encoder pacing.
- `viewing_distance` — yes; subtle font-size effect.
- `pixel_aspect_ratio_e4` — **ignored**. We confirmed this via experiment
  on multiple phones; setting non-1.0 produces no change. GM hardcodes
  `10000` and uses margins instead.
- `UiConfig.content_insets` / `stable_content_insets` — yes, AA places
  dropdowns/dialogs inside.
- `UiConfig.margins` — phones we tested ignore this. GM still sends it
  (split half/half top/bottom and left/right) defensively. We mirror
  that — see
  [jni_session.cpp `applyVideoConfig`](../app/src/main/cpp/jni_session.cpp).

### Key formulas

`getScaledDensity` and `getRealDensity` compute density assuming the inner
content rect (codec frame minus margins) maps **uniformly** onto the
projection area:

```text
fWidth        = projectionArea.width / (codec.width  - width_margin)
scaledDensity = round(reportedDensity / fWidth)              // floor MINIMUM_SCALED_DENSITY = 120
realDensity   = round((codec.height - height_margin) / physicalHeightInches)
```

`MINIMAL_SCALE_DPI_480P` is 96 (configurable via system prop
`gal_min_dpi_480p`); all higher tiers floor at 120 dpi.

## 5. Aspect-ratio strategy: margins, not pixel_aspect

This was the most consequential discovery. AA's protocol gives the
phone **no** way to pre-distort UI pixels (i.e. render non-square pixels
to compensate for downstream non-uniform stretch). The closest field is
`pixel_aspect_ratio_e4`, which the phone ignores.

GM's solution is:

1. Pick a 16:9 codec frame from `{1920×1080, 1280×720, 800×480}`.
2. Compute `width_margin` / `height_margin` so the **inner content rect**
   `(codecW − wm) × (codecH − hm)` matches the panel's **projection area**
   aspect ratio. For a 2914×1134 (≈ 2.57:1) Blazer panel + 1080p codec:
   `hm ≈ 333` → inner `1920×747` ≈ 2.57:1.
3. The car compositor scales the inner rect uniformly onto the projection
   area. Square pixels survive intact.
4. Adjust `density` per the formula above so UI elements arrive at the
   intended physical size after that uniform scale.

We mirror this in [`MarginAutoCalc`](../app/src/main/java/com/openautolink/app/video/MarginAutoCalc.kt)
and the C++ `autoMargins` lambda in
[jni_session.cpp](../app/src/main/cpp/jni_session.cpp). Our renderer goes
one step further than GM: instead of letterboxing the inner rect inside
the panel, we **inflate the SurfaceView so margin pixels overflow the
parent's `clipToBounds()`** — same uniform-scale outcome, but the
projection occupies the full panel rather than reserving panel area for
GM's HMI chrome. See
[ProjectionScreen.kt](../app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt).

### Resolution menu has portrait mirrors

`VideoCodecResolutionType.proto` (in our aasdk fork) exposes the full
enum:

```text
VIDEO_800x480      = 1   // landscape only
VIDEO_1280x720     = 2
VIDEO_1920x1080    = 3
VIDEO_2560x1440    = 4
VIDEO_3840x2160    = 5
VIDEO_720x1280     = 6   // portrait variants
VIDEO_1080x1920    = 7
VIDEO_1440x2560    = 8
VIDEO_2160x3840    = 9
```

For portrait panels we advertise tiers 6–9 instead of 1–5 (no portrait
480p in the spec). For landscape panels both branches work; we keep the
landscape list. See
[jni_session.cpp](../app/src/main/cpp/jni_session.cpp) auto-negotiate
branch.

## 6. Display layout — `PhysicalDisplay` schema

`HuDisplayUtil.loadDisplayConfig` reads JSON files named
`DISPLAY_<SIZE>.json` shipped in the HMI APK assets, one per trim
(`DISPLAY_CHEVY_FF`, `DISPLAY_FF`, `DISPLAY_PORTRAIT_15_INCH`, etc.).
Schema (from `PhysicalDisplay.java` / `HuDisplay.java` / `Area.java`):

```jsonc
{
  "view_distance": 700,                  // mm to driver eye
  "suggested_density": 160,              // logical DPI
  "screen_width": 2914, "screen_height": 1134,
  "safe_area": { "x": …, "y": …, "width": …, "height": … },
  "hu_display": [
    {
      "hu_display_type": "Main",         // "Main"|"BigCard"|"Auxiliary"|"Cluster"
      "fill_projection_area": true,
      "projection_area": { … },          // panel rect reserved for AA
      "content_area":    { … },          // safe sub-rect inside projection_area
      "safe_area":       { … }
    }
  ]
}
```

Important consequence: **GM does *not* render AA full-screen**. They
reserve panel area for native HMI chrome (top status bar + side dock) and
give AA only the `projection_area` sub-rect. Our app full-screens AA by
default; this is a design difference, not a bug.

## 7. Touch coordinates

From `GALManager.startVideo` and `GALDisplay.setTouchScaleRatio`:

```java
mActiveDisplay.setTouchScaleRatio(
    (float) contentSize.getWidth()  / (float) screenRect.width(),
    (float) contentSize.getHeight() / (float) screenRect.height());
```

Touch events from the panel are scaled into the **codec coordinate
space**, then sent on the input service. The phone scales them again to
its render space.

We do the same thing in our pointerInteropFilter overlay — see
[ProjectionScreen.kt](../app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt).
The crucial detail: the touch destination is the **codec inner rect**
(`[0, innerW] × [0, innerH]`), not the full codec frame.

## 8. Surface lifecycle and sleep/wake

Two patterns from `GALManager`:

### Surface binding is delayed until codec resolution arrives

```java
if (surface == null || mActiveDisplay.getCodecResolution() == null) {
    Log.d(TAG, "Video codec resolution not known -- delay MSG_UPDATE_SURFACE");
    mDelayedUpdateSurfaceArgs = someArgs;
}
```

GM does not pre-warm the decoder. We can do better: pre-instantiate
`MediaCodec` at app start using a known-supported tier (1280×720 H.264
baseline-profile is universally supported), so the time from "phone's
first IDR" to "first rendered frame" is just the IDR drain. Especially
beneficial in **manual** mode where we know the exact codec frame
dimensions in advance — see the discussion in
[work-plan.md](work-plan.md). Not yet implemented as of this writing.

### `GALPowerOffManager` — phone-call / VR transients

When the user starts a phone call or voice-recognition session **while
AA is in foreground**, GM does **not** tear down the session. They overlay
a power-off-style screen and track restoration with two atomic flags
(`mShouldRestorePowerOffForPhone`, `mShouldRestorePowerOffForVR`) so the
session is preserved across transient events.

For our reconnect path, the equivalent is: when the car genuinely
suspends, the TCP socket dies and we treat it as fresh-connect; for
transient events (phone call, VR), we should hide the surface but keep the
session — same effect as GM, different mechanism.

## 9. Steering-wheel keycodes (`dispatchKeyEvent`)

| Range | Behaviour |
|---|---|
| 12–15 | Routed to `NavigationFocusManager.handleNavKeyEvent` |
| 16–19 | Phone keys — 16 = `KEYCODE_TEL`, sent only if AA is in foreground |
| 20–23 | Media keys — 20 = `KEYCODE_MEDIA`, sent only if media stream has audio focus |
| default | Routed to `VoiceSessionManager.onVRKeyEvent` (PTT/Hotword) |

We don't currently implement steering-wheel input. When we do, this is
the reference mapping.

## 10. ProjectionState lifecycle

From `gm.connection.ProjectionProfile.ProjectionState`:

```text
INVALID, STOPPING, STOPPED, STARTING, STARTED, SUSPENDED,
STOPPED_BY_CONNECTION_ERROR, STOPPED_BY_WIFI_CONGESTION, STOPPED_BY_MD_BYE
```

`STOPPED_BY_WIFI_CONGESTION` is a real GM-recognised reason — confirms
that WiFi-quality teardowns happen and aren't bugs in our companion.
Worth surfacing as a separate diagnostic category if we see it in field
logs.

## 11. SDV bridges — gated by system prop

| APK | Library | Gate |
|---|---|---|
| `com_gm_sdv_service_canbridge` | `libcanbridge.so` | `persist.sys.gm.sdv_enable` (off by default on retail) |
| `com_gm_sdv_service_udsbridge` | `libudsbridge.so` | **None** — Binder registered at boot |

The CAN bridge is the SDV (Software Defined Vehicle) interface to the
in-vehicle network. The UDS bridge speaks ISO 14229 (diagnostics / ECU
flashing). Neither is relevant to projection; both are interesting future
research targets for diagnostics features. Real protocol surface lives in
the `.so` files (Ghidra/BinaryNinja), not the Java wrappers (which just
load the lib and forward Binder calls).

## 12. APKs catalogued but not analysed in depth

These exist in `recon_dump/apks/` and may be worth deeper inspection
later:

- `com_gm_lcm` — Lifecycle Manager (sleep/wake state machine).
- `com_gm_phonezoneaudio_presentation` — multi-zone audio routing.
- `com_gm_hmi_applecarplay` — CarPlay HMI (parked on
  `feature/carplay-recon`).
- `com_gm_drivemode`, `com_gm_offroad`, `com_gm_valetmode`,
  `com_gm_teenmode`, `com_gm_isaplugin` — feature-flag-gated drive
  modes; not protocol-relevant but interesting for reverse-engineering
  available modes per VIN.
- `com_gm_ultifi_*` — Ultifi SDV service shells around vehicle
  subsystems (body access, climate, propulsion, etc.).
- `com_google_android_apps_automotive_templates_host` — the GAS
  templates host. Reference for non-projected templated apps in AAOS;
  out of scope for the projection layer.

## Decompilation tooling

- `jadx 1.5.3` with `--deobf` and `-r` (resource extraction).
- For >50 MB APKs: `JADX_OPTS="-Xmx12g"` and one APK at a time (a 32-bit
  default heap will OOM mid-decompile).
- The Templates Host APK in our dump was originally truncated (no ZIP
  EOCD record); a re-pull from the head unit produced a complete file.
- See [recon_dump/decompile.ps1](../recon_dump/decompile.ps1) for the
  memory-bounded batch runner.

## Provenance

Captured device: Chevrolet Blazer EV (RPO `aegean`, GM family `aegean`,
SDK 32, fingerprint `gm/aegean_orange/aegean:12/VCUSR5-163/239:user/release-keys`).
Pulled via `adb pull` while in factory engineering mode; 304 packages,
2.53 GB of APKs total. Triage is in [`recon_dump/TRIAGE.md`](../recon_dump/TRIAGE.md)
(gitignored). This file is the durable, in-repo summary intended to
survive after the recon dump is archived.
