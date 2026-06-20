# OpenAutoLink — Work Plan

> **Status note (June 19, 2026):** All core milestones are complete. This file
> contains historical bridge-mode milestone tracking and proto capability plans.
> The current app/companion architecture is documented in [architecture.md](architecture.md)
> and [networking.md](networking.md). Bridge-mode sections are preserved for
> reference but are not active on this branch.

All core milestones (B1–B3, M1–M11) are complete. This file tracks remaining polish, future features, and car testing unknowns.

For the completed milestone history, see [work-plan-archive.md](work-plan-archive.md).

---

## Remaining Polish

Small items from completed milestones that aren't essential but would be nice to have.

### UI Polish
- [ ] Phone battery icon in status area (currently shown as text in stats overlay)
- [ ] Phone signal bars in status area (currently shown as text in stats overlay)
- [ ] Voice session indicator — visual cue when Google Assistant is active on the phone (e.g., mic icon pulse)
- [ ] Incoming call notification overlay (caller name/number from phone_status)
- [ ] Consider: mute touch forwarding during active voice session


---

## 💡 Future Ideas

### Stats Overlay Enhancements
- Audio ring buffer fill level in stats overlay

---

## Car Hardware Reference (2024 Chevrolet Blazer EV)
- **SoC:** Qualcomm Snapdragon
- **Display:** 2914×1134 physical, ~2628×800 usable (nav bar hidden), 2400×960 @ 160dpi as reported by the OS
- **Screens:** 3 displays — main infotainment (17.7" touch), instrument cluster (digital gauges), HUD (windshield)
- **HW Decoders:** H.264 (`c2.qti.avc.decoder`), H.265, VP9 — all 8K@480fps max
- **Network:** USB Ethernet NIC (car USB port), 100Mbps (validate — might be gigabit). Always assigned 192.168.222.108 by GM's AAOS

### VHAL Properties Available on Blazer EV
Confirmed from live car data capture:

| Property | Status | Permission |
|----------|--------|------------|
| Vehicle speed | Available | `CAR_SPEED` |
| Gear selection | Available | `CAR_POWERTRAIN` |
| Parking brake | Available | `CAR_POWERTRAIN` |
| Night mode | Available | `CAR_EXTERIOR_ENVIRONMENT` |
| EV battery level (41550 Wh) | Available | `CAR_ENERGY` |
| EV battery capacity (83010 Wh) | Available | `CAR_INFO` |
| EV charge rate | Available | `CAR_ENERGY` |
| Range remaining (214984 m) | Available | `CAR_ENERGY` |
| Outside temperature (13°C) | Available | `CAR_EXTERIOR_ENVIRONMENT` |
| Ignition state | Available | `CAR_POWERTRAIN` |
| Charge port open/connected | Available | `CAR_ENERGY` |
| Make/Model/Year | Available | `CAR_INFO` |
| Steering angle | **Denied** | `READ_CAR_STEERING_3P` |
| Odometer | **Not exposed** | — |
| Headlights/turn signals | **Denied** | `READ_CAR_EXTERIOR_LIGHTS` |
| Door locks | **Denied** | `CONTROL_CAR_DOORS` |
| HVAC | **Denied** | `CONTROL_CAR_CLIMATE` |
| Tire pressure | **Denied** | `CAR_TIRES_3P` |

---

---



## 📱 App Milestones (New Build)

See [docs/architecture.md](architecture.md) for full component island breakdown and public APIs.

### M1: Connection Foundation
- [x] Gradle project scaffold (min SDK 32, Compose, DataStore)
- [x] Transport island: TCP connect, JSON control parsing, reconnect
- [x] Session state machine (IDLE → CONNECTING → BRIDGE_CONNECTED → PHONE_CONNECTED → STREAMING)
- [x] ProjectionScreen with SurfaceView + connection status HUD

### M2: Video
- [x] MediaCodec decoder with codec selection (H.264/H.265/VP9)
- [x] OAL video frame parsing (16-byte header)
- [x] NAL parsing for SPS/PPS extraction
- [x] Stats overlay (FPS, codec, drops)

### M3: Audio
- [x] 5-purpose AudioTrack slots with ring buffers
- [x] OAL audio frame parsing (8-byte header)
- [x] Audio focus management (request/release/duck)
- [x] Purpose routing (media/nav/assistant/call/alert)
- [x] Dual audio path support — all audio flows through the bridge via TCP: *(unblocked by B1 + B2)*
  - **AA session audio** (aasdk channels): media, navigation, alerts — decoded by aasdk, sent as PCM over OAL *(B1 done — bridge sends OAL audio frames)*
  - **BT HFP audio** (phone → SBC Bluetooth): phone calls, voice assistant — bridge captures SCO audio from HFP and forwards as PCM over OAL with call/assistant purpose *(B2 done — bridge has SCO↔OAL bridge)*
- [x] Detect active audio purpose and manage focus (e.g., duck media during call)
- [x] Handle call audio transitions: ring, in-call, call end

### M4: Touch + Input
- [x] Touch forwarding with coordinate scaling
- [x] Multi-touch (POINTER_DOWN/UP for pinch zoom)
- [x] JSON touch serialization to control channel

### M5: Microphone + Voice
- [x] Timer-based mic capture from car's mic (via AAOS AudioRecord)
- [x] Send on audio channel (direction=1)
- [x] Mic source preference: car mic (default) or phone mic, toggled in Settings
- [x] Bridge mic_start/mic_stop control messages *(unblocked by B1)*
- [x] Coordinate mic routing: bridge forwards mic PCM to aasdk for AA voice, and to BT SCO for phone calls *(unblocked by B2 — bridge routes by purpose)*

### M6: Settings + Config
- [x] DataStore preferences (codec, resolution, fps, display mode) — basic prefs done in M1, display mode added with M2
- [x] Settings Compose UI — bridge host/port, display mode selector
- [x] Config sync: app → bridge → echo *(unblocked by B1)*
- [x] Bridge discovery (mDNS + manual IP)

### M7: Vehicle Integration
- [x] GNSS forwarding (LocationManager → NMEA → bridge)
- [x] VHAL properties (37 properties via Car API reflection)
- [x] Navigation state display + maneuver icons

### M8: Cluster Display
- [x] Cluster `CarAppService` + `ClusterMainSession` (GM path: `NavigationManager.updateTrip()` relay)
- [x] `OalClusterSession` fallback (standard AAOS path: direct `NavigationTemplate` rendering with `RoutingInfo`)
- [x] Cluster navigation: `Maneuver.TYPE_*` enums + distance + road name via `Trip` builder
- [x] `MediaBrowserService` + `MediaSession` for cluster media: album artwork, track info
- [x] Bridge → app: `album_art_base64` field in `media_metadata` control message
- [x] `ClusterIconShimProvider` for Templates Host icon caching (GM-specific debug workaround)
- [x] Register `ClusterIconShimProvider` only in the debug manifest with the GM Templates Host authority (`com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`)
- [x] Handle GM restrictions: `ClusterManager` detects session death via `ClusterBindingState.sessionAlive`, re-launches `CarAppActivity` binding chain with backoff
- [x] Cluster Navigation setting is authoritative: disabled stops scheduled binding, disables the service component, clears cluster state, and prevents `CarAppActivity` launches
- [x] Host validation tightened: exported `OalClusterService` uses `HostValidator.Builder` instead of `ALLOW_ALL_HOSTS_VALIDATOR`
- [x] Cluster rebind preserves active route state; `restartClusterBinding()` no longer clears `ClusterNavigationState`
- [x] `navigationStarted()` / `updateTrip()` failures get bounded retry against the last still-current `ManeuverState`
- [x] Base64 maneuver icon decode uses a small lifecycle-cleared cache with vector fallback
- [x] Fallback rendering if cluster service is blocked: `launchClusterBinding()` catches and suppresses failures, cluster degrades silently while `MediaSession` continues
- [x] `ClusterBindingState` tracking + auto-relaunch after teardown
- [x] Bridge: aasdk `NavigationStatusService` configured with `InstrumentClusterType::IMAGE` + `image_options` (256×256, 32-bit)
- [x] Bridge: aasdk `NavigationStatusService` extended with `onNavigationState()` + `onCurrentPosition()` handlers for `NavigationState` proto (msg 32774) and `NavigationCurrentPosition` (msg 32775) — 42 maneuver types
- [x] Bridge: forward maneuver icon PNG bytes from `NavigationNextTurnEvent.image` (IMAGE mode) → base64-encode → `nav_image_base64` field in `nav_state` control message
- [x] App: when `nav_image_base64` present, use `Maneuver.TYPE_UNKNOWN` + `CarIcon.Builder(IconCompat.createWithBitmap(...))` — AA icon is source of truth
- [x] App: fall back to `Maneuver.TYPE_*` enum mapping + bundled VectorDrawable icon when no image available
- [x] Bundled VectorDrawable icon set (44 drawables from CarPlay `cp_maneuver_*` set) + `ManeuverIconRenderer` mapping `ManeuverType` → drawable resource
- [x] `ClusterManager` utility: enables/disables CarAppService component, launches/restarts cluster binding, handles teardown recovery

### M9: Steering Wheel Controls
- [x] Media button mapping: skip forward, skip back, play/pause via `KeyEvent` interception
- [x] Volume controls via `AudioManager` or `KeyEvent`
- [x] Voice button interception: intercept the AAOS voice/assistant `KeyEvent` (currently launches Google Assistant) and forward as AA voice trigger to activate Gemini on the phone
- [x] GM F-key mapping: GM sends KEYCODE_F7 (137) instead of standard KEYCODE_MEDIA_NEXT — added GM_FKEY_TO_AA mapping table
- [ ] Investigate `KEYCODE_VOICE_ASSIST` / `KEYCODE_SEARCH` interception feasibility on GM AAOS — **CONFIRMED blocked** at system level. AccessibilityService is the next approach
- [ ] Confirm F6/F8/F9 mappings for track-prev and play/pause on GM steering wheel

### M10: Polish
- [x] Diagnostics screen
- [x] Error recovery (reconnect, codec reset)
- [x] Display modes (fullscreen, system bars, custom viewport) — pulled forward, implemented with M2; custom viewport added later
- [x] Custom viewport editor — draggable edge bars, aspect ratio lock, presets, manual pixel input
- [x] Overlay buttons (settings, stats) — pulled forward, draggable floating buttons
- [x] App icon and logo — adaptive icon from brand asset
- [x] Stats for nerds overlay — monospace panel with session/video stats

### M10b: Modern Navigation Data (Lane Guidance + Enriched Nav)

**Purpose:** Upgrade from AA's deprecated NavigationNextTurnEvent/DistanceEvent (msg 32772/32773) to the modern NavigationState (msg 32774) and NavigationCurrentPosition (msg 32775) protobuf messages. This unlocks lane guidance, richer maneuver types, cue text, roundabout details, pre-formatted distances, destination addresses, and formatted ETA.

**Bridge Side:**
- [x] Override `onNavigationState()` — parse `NavigationStep.lanes`, `NavigationManeuver`, `NavigationCue`, `NavigationRoad`, `NavigationDestination`
- [x] Override `onCurrentPosition()` — parse `NavigationStepDistance`, `NavigationDestinationDistance`, `NavigationDistance.display_value/display_units`, current road, formatted ETA
- [x] Map 43 modern `NavigationType` enums to wire strings (vs legacy's 19 sparse enums)
- [x] Map `LaneDirection.Shape` enums (0-9) to wire strings for lane guidance
- [x] Send enriched `nav_state` JSON with new fields: `lanes`, `cue`, `roundabout_exit_number`, `roundabout_exit_angle`, `display_distance`, `display_distance_unit`, `current_road`, `destination`, `eta_formatted`, `time_to_arrival_seconds`
- [x] Suppress legacy turn/distance events when modern nav is active (`has_modern_nav_` flag)
- [x] Continue extracting maneuver icon PNG from legacy turn events (IMAGE mode) — cached for inclusion in modern nav_state

**App Side:**
- [x] Extend `ControlMessage.NavState` with new fields: `lanes`, `cue`, `roundaboutExitNumber`, `displayDistance`, `displayDistanceUnit`, `currentRoad`, `destination`, `etaFormatted`, `timeToArrivalSeconds`
- [x] Parse lane guidance JSON array in `ControlMessageSerializer`
- [x] Extend `ManeuverState` with `lanes: List<LaneInfo>`, `cue`, `roundaboutExitNumber`, `currentRoad`, `destination`, `etaFormatted`
- [x] Extend `ManeuverType` enum: added `KEEP_LEFT/RIGHT`, `MERGE_UNSPECIFIED`, `ON_RAMP_SLIGHT/SHARP/U_TURN` variants, `OFF_RAMP_SLIGHT` variants, `ROUNDABOUT_ENTER_AND_EXIT_CW/CCW`, `DESTINATION_STRAIGHT`, `FERRY_TRAIN`
- [x] Use pre-formatted distance from bridge when available (respects phone locale)
- [x] Build `Lane` + `LaneDirection` objects for cluster `Step.Builder.addLane()`
- [x] Set `Step.setRoad()` for road name (separate from cue text)
- [x] Use cue text for `Step.setCue()` when available (e.g. "Turn right onto Main St")
- [x] Updated `ManeuverMapper`, `ManeuverIconRenderer`, `mapManeuverTypeToCarApp` for all new types

### M11: Remote Diagnostics (Car Testing Enabler)

**Purpose:** Since GM AAOS has no ADB access, we need a way to observe app behavior in real-time on the car. The app sends structured logs and periodic telemetry back to the bridge over the existing control channel (port 5288). The bridge writes them to stderr, visible via `journalctl` over SSH.

This unblocks all car-specific validation items listed in the Car Testing Unknowns section below.

**App Side:**
- [x] `diagnostics/` island: `RemoteDiagnostics` interface + `RemoteDiagnosticsImpl`
- [x] `app_log` message type: structured log events with `ts`, `level`, `tag`, `msg`
- [x] `app_telemetry` message type: periodic (5s) snapshot of video/audio/session/cluster stats
- [x] Rate limiter: max 20 log messages/second (ring buffer, newest wins)
- [x] DataStore preference: remote diagnostics enabled (default: off)
- [x] DataStore preference: minimum log level to send (default: INFO)
- [x] Settings UI: toggle + log level selector in diagnostics tab
- [x] Instrument key subsystems with diagnostic log points:
  - `video`: codec selection, codec reset, first frame timing, decode errors
  - `audio`: AudioTrack creation, underruns, purpose routing, focus changes
  - `cluster`: bind/unbind events, GM kill detection, rebind attempts
  - `vhal`: property availability per-property, subscription errors
  - `input`: key event interception success/failure (voice button, media keys)
  - `transport`: connection timing, reconnect events, channel failures
  - `system`: Android version, SoC, display metrics (sent once on connect)
- [x] Add `app_log` and `app_telemetry` to `ControlMessageSerializer` (app→bridge direction)

**Bridge Side:**
- [x] Handle `app_log` in `on_app_json_line()`: write to stderr with `[CAR]` prefix + level + tag
- [x] Handle `app_telemetry` in `on_app_json_line()`: write to stderr with `[CAR] TELEM` prefix
- [x] Both types are fire-and-forget — no response, no parsing beyond prefix formatting

**Protocol:** See [docs/protocol.md](protocol.md) → Remote Diagnostics Channel section.

---

## 🚗 Car Testing Unknowns (GM AAOS)

These items can only be validated on the actual GM head unit. No emulator can answer them. Remote diagnostics (M11) is the primary tool for investigating each one — the app logs relevant events, and we observe via SSH on the bridge.

### Cluster Service (M8)
| Unknown | How to test | What to log | Status |
|---------|------------|-------------|--------|
| Does GM kill third-party cluster services? | Deploy app, bind cluster, monitor lifetime | `tag=cluster`: bind time, alive duration, destroy event, reason if available | **Unknown** — needs longer drive session |
| How long does it stay alive before kill? | Timestamp bind vs destroy, compute delta | `tag=cluster`: `ClusterMainSession created`, `destroyed after Nms` | **Unknown** — needs longer drive session |
| Does GM's Templates Host work with our `NavigationTemplate`? | Send nav state, check cluster display | `tag=cluster`: `Trip.Builder` success/failure, any `RemoteException` | **CONFIRMED** — GM renders Trip data via OnStarTurnByTurnManager. Arrow + road name displayed correctly on instrument cluster |
| `ClusterIconShimProvider` — does GM query it? | Deploy, log `ContentProvider.query()` calls | `tag=cluster`: `insert()`, `query()`, `openFile()` events with URI and caller package | **Instrumented** — provider is now manifest-registered; needs car validation |
| Is re-binding after kill effective? | Track rebind count over a drive session | `tag=cluster`: `rebind attempt #N`, success/failure, preserved maneuver after rebind | **IMPLEMENTED** — state is preserved across rebind; needs longer drive validation |
| Fallback if cluster is fully blocked | No cluster display — degrade gracefully, hide cluster settings | Log final determination so we can document for users | N/A — cluster works |
| Nav cancel clears cluster | Cancel nav on phone, verify cluster clears | `tag=cluster`: `navigationEnded()` called | **FIXED** — was missing `nav_state_clear` message. Bridge now sends it on `status!=active` |

### Steering Wheel Controls (M9)
| Unknown | How to test | What to log | Status |
|---------|------------|-------------|--------|
| `KEYCODE_VOICE_ASSIST` interception feasibility | Register `KeyEvent` handler, attempt intercept | `tag=input`: keycode received, intercepted=true/false, forwarded to AA | **BLOCKED** — system consumes before app sees it |
| Does GM route voice button to system assistant? | Press voice button, log what the app sees | `tag=input`: whether our app receives the event at all, or if system consumes it | **CONFIRMED** — app sees nothing, only audio focus loss |
| Accessibility service approach viable? | If `KeyEvent` interception fails, try `AccessibilityService` | `tag=input`: accessibility service bind result, events received | **Next step** |
| Media button reliability | Press all steering wheel buttons, log each | `tag=input`: per-keycode: received, forwarded, acknowledged by bridge | **PARTIAL** — F7=track-next confirmed, need F6/F8/F9 |

### Video/Audio on Real Hardware
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Actual FPS sustained on car | Run projection, observe telemetry | `app_telemetry`: fps field (rolling average), dropped frame count |
| H.265 vs H.264 performance | Toggle codec in settings, compare fps/drops | `tag=video`: codec selected, first frame timing, steady-state fps |
| Audio latency perception | Drive with nav + media, subjective evaluation | `tag=audio`: first audio frame timing, underrun counts |
| First-frame time (cold start) | Kill app, relaunch, measure connection→first render | `tag=video`: timestamps at connect, codec config, first IDR, first render |
| Reconnect quality (car off/on) | Turn car off, back on, observe reconnect | `tag=transport`: disconnect detected, reconnect attempts, success time; `tag=video`: first IDR after reconnect |

### VHAL Properties
| Unknown | How to test | What to log |
|---------|------------|-------------|
| Which of the 37 properties are available on GM? | Subscribe all, log success/failure per property | `tag=vhal`: per-property availability result, any exceptions |
| Property value format/range on GM | Log actual values from car sensors | `tag=vhal`: sample values for speed, gear, turn signals, battery |
| Does GM restrict `CarPropertyManager` for third-party apps? | Attempt subscription, log any security exceptions | `tag=vhal`: `SecurityException` or `IllegalArgumentException` per property |

### Network
| Unknown | How to test | What to log |
|---------|------------|-------------|
| USB Ethernet speed (100Mbps or gigabit?) | Log link speed if available via `ConnectivityManager` | `tag=system`: link speed, interface name |
| Is 192.168.222.108 always assigned? | Check assigned IP on car | `tag=system`: IP address on USB NIC |
| Any firewall/iptables rules blocking our ports? | Connection success/failure timing | `tag=transport`: per-port connect latency, timeout vs refused vs success |

### Testing Workflow
1. Build APK with remote diagnostics enabled by default for car testing builds
2. Install on car (sideload via ADB)
3. SSH to bridge: `journalctl -u openautolink.service -f | grep '\[CAR\]'`
4. Use the car normally — diagnostics stream in real time
5. After session: `journalctl -u openautolink.service --since "1 hour ago" | grep '\[CAR\]' > car-session.log`
6. Review logs, update this section with findings, check off unknowns as resolved

---

## 🛠️ Dev Tooling & CI/CD

### CI/CD (GitHub Actions)
- [x] **Release APK workflow** (`.github/workflows/release-apk.yml`) — triggers on GitHub Release, builds signed APK, attaches to release
- [x] **Release Bridge workflow** (`.github/workflows/release-bridge.yml`) — triggers on GitHub Release, cross-compiles ARM64 binary via QEMU Docker, attaches to release
- [x] **GitHub Pages** — project documentation hosting
- [x] **Branch protection** — PRs to main require 1 approving review (admin exempt)

### Emulator Testing Setup
- [x] **Two-AVD approach** documented in `docs/testing.md`:
  - **BlazerEV_AAOS** (Google APIs, no Play Store): `adb root`, VHAL injection, primary testing
  - **DD_AAOS_33** (Distant Display + Play Store): visual cluster rendering on secondary display
- [x] Both AVDs configured at 2400×960 @ 160dpi to match the real GM Blazer EV
- [x] **DD_AAOS_33 display fix**: secondary displays resized (400×240 + 800×240) to prevent oversized emulator window
- [x] **VHAL permissions** added to AndroidManifest.xml: `CAR_SPEED`, `CAR_ENERGY`, `CAR_POWERTRAIN`, `CAR_EXTERIOR_ENVIRONMENT`, `CAR_INFO` — only properties confirmed available on 2024 Blazer EV
- [x] **VHAL injection commands** documented for Blazer EV values: speed, gear, EV battery, temp, range, night mode, parking brake

### CI / Automated Testing
- [x] **CI workflow** (`.github/workflows/ci.yml`) — runs on every PR and push to main
  - **unit-tests job**: JDK 21, Android SDK, `./gradlew :app:testDebugUnitTest`, uploads test reports
  - **emulator-smoke-test job** (main only): boots AAOS emulator, installs APK, verifies app process is alive
- [x] **Test result badge** in README — links to CI workflow status

---

## 🧭 Development Workflow

### One Milestone Per Conversation
Each milestone should be completed in a **single Copilot conversation**. When a milestone's exit criteria are met, **stop and tell the user to start a new conversation** for the next milestone. This keeps context focused and avoids degraded output from overly long conversations.

### How to Start Each Conversation
Open a new Copilot chat and say:
> "Let's build M[N]: [milestone name]. Start with [first task]."

Copilot will read the instruction files, repo memory, and this work plan automatically — no need to re-explain the project.

### Within a Milestone
- Prompt by island or logical task (e.g., "Build the Transport island", "Add unit tests for JSON parsing")
- Let Copilot finish each piece, verify no compile errors, then move to the next
- Copilot should check off `[ ]` items in this plan as they're completed

### Milestone Boundaries
- **Do not start the next milestone in the same conversation** — context quality degrades
- Between milestones: build, deploy to device/emulator, test manually, note any issues
- Start the next conversation with any issues or adjustments discovered during testing

### Parallel Work
Parallel Copilot sessions are **not recommended** for this project:
- Sessions don't communicate or coordinate file writes
- Build state isn't shared — one session can't see another's compile errors
- Island architecture helps in theory, but merge conflicts aren't worth the risk
- Sequential milestones have hard dependencies (M2 needs M1's transport, M3 needs M1's transport, M4 needs M2's surface, etc.)

### If a Conversation Gets Too Long
If Copilot starts losing context or producing lower quality output mid-milestone, it's fine to start a new conversation and say:
> "Continuing M[N]. [Island X] is done, [Island Y] still needs [specific tasks]."

---

---

## 💡 Future Ideas

### Two-Way Config Sync
- App populates settings dialog from bridge echo, showing actual running config

### Stats Overlay Enhancements
- Parse SPS/PPS for actual stream resolution (not just codec init dims)
- Audio: PCM frame count, ring buffer fill level

---

## Car Hardware Reference (2024 Chevrolet Blazer EV)
- **SoC:** Qualcomm Snapdragon
- **Display:** 2914×1134 physical, ~2628×800 usable (nav bar hidden), 2400×960 @ 160dpi as reported by the OS
- **Screens:** 3 displays — main infotainment (17.7" touch), instrument cluster (digital gauges), HUD (windshield)
- **HW Decoders:** H.264 (`c2.qti.avc.decoder`), H.265, VP9 — all 8K@480fps max
- **Network:** USB Ethernet NIC (car USB port), 100Mbps (validate — might be gigabit). Always assigned 192.168.222.108 by GM's AAOS

### VHAL Properties Available on Blazer EV
Confirmed from live car data capture:

| Property | Status | Permission |
|----------|--------|------------|
| Vehicle speed | Available | `CAR_SPEED` |
| Gear selection | Available | `CAR_POWERTRAIN` |
| Parking brake | Available | `CAR_POWERTRAIN` |
| Night mode | Available | `CAR_EXTERIOR_ENVIRONMENT` |
| EV battery level (41550 Wh) | Available | `CAR_ENERGY` |
| EV battery capacity (83010 Wh) | Available | `CAR_INFO` |
| EV charge rate | Available | `CAR_ENERGY` |
| Range remaining (214984 m) | Available | `CAR_ENERGY` |
| Outside temperature (13°C) | Available | `CAR_EXTERIOR_ENVIRONMENT` |
| Ignition state | Available | `CAR_POWERTRAIN` |
| Charge port open/connected | Available | `CAR_ENERGY` |
| Make/Model/Year | Available | `CAR_INFO` |
| Steering angle | **Denied** | `READ_CAR_STEERING_3P` |
| Odometer | **Not exposed** | — |
| Headlights/turn signals | **Denied** | `READ_CAR_EXTERIOR_LIGHTS` |
| Door locks | **Denied** | `CONTROL_CAR_DOORS` |
| HVAC | **Denied** | `CONTROL_CAR_CLIMATE` |
| Tire pressure | **Denied** | `CAR_TIRES_3P` |
