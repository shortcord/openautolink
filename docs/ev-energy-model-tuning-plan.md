# EV Energy Model Tuning — Implementation Plan

Status: **PLANNED — not yet implemented**

Goal: let users tune the `VehicleEnergyModel` (VEM, sensor type 23) we send to
Google Maps so battery-on-arrival / battery-on-return estimates better match
what AAOS Google Maps shows natively in the car.

See [docs/ev-energy-model-reverse-engineering.md](ev-energy-model-reverse-engineering.md)
for the full reverse-engineered protobuf and what Maps does with it.

---

## Background — what's actually different

### What AAOS Google Maps does natively (in the car)
- Has full access to a server-side, per-make/model/year EV vehicle profile
  (charge curves, aero coefficient, efficiency curves, real max DC power,
  battery thermal model). Google does not expose this profile via any public
  API.
- Combined with live VHAL data, it computes very accurate arrival % using
  many fields we don't currently populate.

### What we do today
File: [app/src/main/cpp/jni_session.cpp](../app/src/main/cpp/jni_session.cpp) — `sendEnergyModelSensor()`.

We build a **stripped** `VehicleEnergyModel` with only:
- `battery.max_capacity` ← `INFO_EV_BATTERY_CAPACITY` (VHAL)
- `battery.min_usable_capacity` ← `EV_BATTERY_LEVEL` (VHAL — Maps reads this as current SOC)
- `battery.reserve_energy` ← 5 % of capacity (hardcoded)
- `battery.max_charge_power_w` ← live `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` if > 0 else **150 000 W**
- `battery.max_discharge_power_w` ← **150 000 W** (hardcoded)
- `consumption.driving.rate` ← `(currentWh / rangeRemaining_m) × 1000` — derived from the **car's own** RANGE_REMAINING estimate
- `consumption.auxiliary.rate` ← **2.0 Wh/km** (hardcoded)
- `consumption.aerodynamic.rate` ← **0.36** (hardcoded)
- `charging_prefs.mode` ← 1 (standard)
- All other fields (charge curves, efficiency, thermal, calibration, etc.) are unset.

### Why our number diverges from native AAOS Maps
1. Driving Wh/km is anchored to GM's RANGE_REMAINING which is conservative / seasonal.
2. Aero coefficient 0.36 is too high for a Blazer EV (~0.29 Cd).
3. Max charge power 150 kW is low (Blazer EV ~190 kW DCFC).
4. Reserve 5 % is arbitrary; Maps may treat it as unusable.
5. We don't send charging curves or efficiency points, so Maps can't model
   speed- or SOC-dependent behavior.

---

## Why not a Google Web API

Investigated and **rejected**:
- Google Maps **Routes API** EV mode requires us to *supply* `consumptionRateKwhPerKm` — there's no endpoint that returns Google's internal vehicle profile.
- No Maps SDK or Android Auto SDK exposes the per-vehicle EV model.
- The `VehicleEnergyForecast` Binder callback only reflects the model we sent.
- TOS / billing / quota / offline-use issues make per-session API calls a non-starter even if it existed.

Decision: rely on **EPA-prefilled defaults** + **user override sliders**.
The EPA table ships **bundled in the APK** so it works fully offline — the
head unit typically has no internet of its own (Maps' traffic is tunneled
through the phone, but the head unit itself usually sits on a private AP).
Any feature that requires a live network call must be **opt-in, cached, and
non-blocking**.

---

## Design

### Data model (DataStore preferences)

Add to [`AppPreferences`](../app/src/main/java/com/openautolink/app/data/AppPreferences.kt):

```kotlin
val EV_TUNING_ENABLED            = booleanPreferencesKey("ev_tuning_enabled")          // false = default calc
val EV_DRIVING_MODE              = stringPreferencesKey("ev_driving_mode")             // "derived" | "manual" | "multiplier"
val EV_DRIVING_WH_PER_KM         = intPreferencesKey("ev_driving_wh_per_km")           // used when mode=manual
val EV_DRIVING_MULTIPLIER_PCT    = intPreferencesKey("ev_driving_multiplier_pct")      // 50..150, used when mode=multiplier
val EV_AUX_WH_PER_KM_X10         = intPreferencesKey("ev_aux_wh_per_km_x10")           // *10 for 0.1 step
val EV_AERO_COEF_X100            = intPreferencesKey("ev_aero_coef_x100")              // *100 for 0.01 step
val EV_RESERVE_PCT               = intPreferencesKey("ev_reserve_pct")                 // 0..15
val EV_MAX_CHARGE_KW             = intPreferencesKey("ev_max_charge_kw")               // 50..350
val EV_MAX_DISCHARGE_KW          = intPreferencesKey("ev_max_discharge_kw")            // 50..300
```

Defaults reproduce **current** behavior so a fresh install behaves identically:

| Pref | Default |
|---|---|
| `EV_TUNING_ENABLED` | `false` |
| `EV_DRIVING_MODE` | `"derived"` |
| `EV_DRIVING_WH_PER_KM` | `160` |
| `EV_DRIVING_MULTIPLIER_PCT` | `100` |
| `EV_AUX_WH_PER_KM_X10` | `20` (= 2.0) |
| `EV_AERO_COEF_X100` | `36` (= 0.36) |
| `EV_RESERVE_PCT` | `5` |
| `EV_MAX_CHARGE_KW` | `150` |
| `EV_MAX_DISCHARGE_KW` | `150` |

### EPA prefill table (optional, phase 2)

Static asset `app/src/main/assets/ev_profiles.json` keyed by `make|model|year`:
```json
{
  "Chevrolet|C234|2024": {"combined_wh_per_km": 187, "max_charge_kw": 190},
  "Chevrolet|Bolt EUV|2023": {"combined_wh_per_km": 175, "max_charge_kw": 55},
  "...": {}
}
```
Source: EPA fueleconomy.gov API (combined kWh/100mi → Wh/km). Generated
offline by a script in `scripts/build-ev-profiles.py`. When available for the
detected vehicle, used as the default for `EV_DRIVING_WH_PER_KM` and
`EV_MAX_CHARGE_KW` even when tuning is disabled — purely cosmetic prefill,
still respects the master toggle.

### UI — new screen

New file: `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelScreen.kt`

Reachable via a new row in [`SettingsScreen.kt`](../app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt)
under the existing vehicle/diagnostics section: **"EV Range Estimates → Tweak"**.

Layout:

1. **Header card — explanation** (always visible, plain language):
   > Google Maps shows you a battery-percent estimate when you arrive at a
   > destination and when you get back home. Behind the scenes, Maps needs
   > to know how much energy your car uses per kilometer.
   >
   > **What Google does in the car natively:** Maps has a built-in profile
   > for many EVs that includes how the battery behaves at different
   > charge levels, how aerodynamic the car is, and how fast it can charge.
   > That profile is private — there's no way for an app to ask Google
   > what numbers it's using.
   >
   > **What OpenAutoLink does:** we send Maps a simpler model built from
   > what your car reports — its current battery level, its total capacity,
   > and the range estimate the dashboard shows. We add reasonable defaults
   > for the rest. This is usually close, but the dashboard's range tends
   > to be conservative, so Maps may show lower battery-on-arrival than
   > the native AAOS Maps in your car.
   >
   > If you want, you can override the values below and send Maps a more
   > optimistic (or pessimistic) profile until the numbers match what you
   > see side-by-side.

2. **Master toggle**: `Use default calculation` / `Customize`.
   When OFF, all sliders are disabled and grayed; we send today's behavior unchanged.

3. **Sliders / controls** (enabled only when toggle is on):
   - Driving consumption (segmented):
     - `Derived from car` (current)
     - `Multiplier`: 0.50× – 1.50×, default 1.00× (applied to derived)
     - `Manual`: 80–300 Wh/km, default 160
   - Auxiliary: 0.0–10.0 Wh/km (step 0.1)
   - Aerodynamic coefficient: 0.20–0.45 (step 0.01)
   - Reserve %: 0–15
   - Max charge power: 50–350 kW
   - Max discharge power: 50–300 kW

4. **Live readout card**: shows last sent VEM values (current Wh, capacity Wh, computed Wh/km, what Maps will compute as SOC %).

5. **"Send now" button**: forces an immediate VEM update so changes show up in Maps within seconds (bypass the 30 s throttle).

6. **"Reset to defaults" button**.

### ViewModel

New file: `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelViewModel.kt`. Standard `StateFlow` pattern, observes `AppPreferences`, exposes setters that call `appPreferences.set...()`.

### Plumbing — making the values flow into VEM

The sliders need to reach the JNI `sendEnergyModelSensor()`. Options:

**Option A (recommended) — pass overrides through the JNI call**

Extend the JNI signature:
```kotlin
fun sendEnergyModel(
    batteryLevelWh: Int, batteryCapacityWh: Int, rangeM: Int, chargeRateW: Int,
    drivingWhPerKm: Float,        // -1 = use derived
    auxWhPerKm: Float,
    aeroCoef: Float,
    reservePct: Float,
    maxChargeW: Int,
    maxDischargeW: Int,
)
```
- C++ uses the override values directly; if `drivingWhPerKm < 0`, falls back to the existing derived formula.
- `SessionManager` reads tuning prefs once at session start and on each VEM send (already throttled to 30 s).

**Option B — second JNI setter**: `setEnergyModelOverrides(...)` cached on the C++ side, applied inside `sendEnergyModelSensor`. Cleaner separation but more state.

Lean toward **A** — single source of truth, no hidden state in C++.

### "Send now" path

`EvEnergyModelViewModel.forceSend()` →
`SessionManager.forceSendEnergyModel()` → reads latest VHAL snapshot from
`VehicleDataForwarderImpl.lastVehicleData` → calls
`aasdkSession.sendEnergyModel(...)` directly, bypassing the 30 s throttle.

If session is not connected, show a snackbar: "Connect to phone first."

### Logging

Existing `DiagnosticLog.i("vem", ...)` line in `SessionManager` already prints what we send. Extend it to include override flags:
```
vem: level=53900Wh cap=85660Wh range=283000m charge=0W [tuning=ON drv=manual:165 aux=2.2 aero=0.30 res=4% chg=190kW]
```

---

## Files touched

| File | Change |
|---|---|
| `app/src/main/java/com/openautolink/app/data/AppPreferences.kt` | + 8 prefs, defaults, flows, setters |
| `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelScreen.kt` | **NEW** — Compose UI |
| `app/src/main/java/com/openautolink/app/ui/settings/EvEnergyModelViewModel.kt` | **NEW** |
| `app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt` | + nav row "EV Range Estimates" |
| `app/src/main/java/com/openautolink/app/MainActivity.kt` (or nav graph) | + route `ev_energy_model` |
| `app/src/main/java/com/openautolink/app/session/SessionManager.kt` | read tuning prefs, pass to `sendEnergyModel`, add `forceSendEnergyModel()` |
| `app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt` | extend `sendEnergyModel(...)` signature |
| `app/src/main/cpp/aasdk_jni.cpp` | extend JNI binding |
| `app/src/main/cpp/jni_session.h` / `.cpp` | extend `sendEnergyModelSensor` to accept overrides; `< 0` → derive |
| **Phase 2** `app/src/main/assets/ev_profiles.json` | EPA prefill table |
| **Phase 2** `scripts/build-ev-profiles.py` | offline generator from EPA data |

---

## Phasing

**Phase 1 — manual sliders (the actual ask)**
- Prefs + UI + plumbing through JNI.
- Master toggle off by default → zero behavior change for existing users.
- Defaults under "Customize" reproduce current hardcoded values.

**Phase 2 — EPA prefill (nice-to-have)**
- Static JSON asset, **shipped inside the APK** — works fully offline. No
  network call at runtime. The head unit often has no internet
  (phone-hotspot mode pipes Maps' traffic *through* the phone, but the head
  unit itself is on a private AP); any feature that requires a live HTTP
  call would silently fail in the most common setup.
- On first session detect make/model/year, prefill `EV_DRIVING_WH_PER_KM`
  and `EV_MAX_CHARGE_KW` from the bundled JSON.
- Even when tuning is OFF, can optionally use prefilled `driving.rate`
  instead of the GM-range-derived value behind a sub-toggle "Use EPA
  combined as baseline".
- The EPA table itself is **regenerated offline** by
  `scripts/build-ev-profiles.py` and committed to the repo. New vehicles
  arrive via app updates, not runtime fetches.

**Optional Phase 2b — online refresh (gated, off by default)**
- If we ever add a runtime fetch (e.g. an opt-in "Update EV profile
  database" button), it must:
  - Be **off by default** and clearly labeled as requiring internet.
  - Probe connectivity first; fail silent-and-fast (no spinner > 2 s) when
    offline.
  - Cache the result so subsequent sessions work without internet.
  - Never block VEM sending — VEM always uses the last-known-good profile.
- Same rule applies to any future "ask Google for a route preview to
  cross-check arrival %" idea: opt-in, cached, never on the hot path.

**Phase 3 (later) — fuller VEM**
- Send simple synthetic charging curves (single point at max power) — quick win for charging-stop accuracy.
- Send efficiency.normal[] from EPA city/highway split.

---

## Open questions for implementation time

- Should **"Send now"** also be a long-press action on a session-state pill in the projection overlay, for quick A/B testing without leaving Maps? Probably yes, but ship the screen first.
- Do we hide the screen on non-EV vehicles (no `INFO_EV_BATTERY_CAPACITY`)? Probably **show but disable** with explanatory text — keeps the menu predictable.
- Persist a per-vehicle profile keyed by VIN (when readable) so two cars sharing one head-unit install (rare) keep separate tunings? Out of scope for v1.

---

## Acceptance test

1. Fresh install → toggle OFF → VEM bytes match the current build byte-for-byte.
2. Toggle ON, leave defaults → VEM bytes still match (defaults equal hardcodes).
3. Set driving = manual 165 Wh/km, aero = 0.29, charge = 190 kW → "Send now" → log line shows new values; AAOS Maps arrival % updates within ~3 s.
4. Toggle OFF again → log line returns to derived value.
5. Reconnect after car-off → tuning persists; first VEM after reconnect uses tuned values.
