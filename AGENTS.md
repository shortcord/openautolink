# OpenAutoLink Agent Guide

## Project Snapshot

- OpenAutoLink is a wireless Android Auto project for AAOS head units.
- The active Android code is split into two Gradle modules:
  - `app/` — AAOS head-unit app, Kotlin/Compose plus aasdk JNI C++.
  - `companion/` — phone-side Android app, Kotlin/Compose and Nearby Connections.
- Native Android integration lives in `app/src/main/cpp/`; always check the Kotlin and C++ sides together when changing JNI-facing behavior.
- `external/opencardev-aasdk/` is a forked aasdk submodule used by the app. Do not leave dirty submodule edits; commit them in the submodule and update the parent pointer when that is intentional.
- `external/opencardev-openauto/` is reference material unless a task explicitly says otherwise.
- Bridge/SBC mode has been removed from this branch. Do not reference or recreate `bridge/` workflows unless the user explicitly asks to recover historical code from another branch.

## High-Priority Engineering Rules

- Prioritize projection performance: connection latency, first rendered frame, audio stability, touch latency, and clean reconnects matter more than feature breadth.
- Do not add work to hot audio/video/touch paths unless it is necessary and bounded.
- On reconnect or resume paths, preserve clean state: flush stale media, wait for IDR before rendering video, avoid stale audio buffers, and avoid noisy UI errors.
- Keep component islands independent. Use existing public interfaces and the session orchestrator instead of cross-package shortcuts.
- Prefer root-cause fixes over defensive patches that hide lifecycle, threading, or protocol errors.

## Kotlin / Android Conventions

- Use Kotlin with Java 17 target and Jetpack Compose patterns already present in the repo.
- Compose screens should observe `StateFlow` from ViewModels; keep business logic out of composables.
- Use repositories or injectable collaborators for DataStore, transport, VHAL, and platform access.
- Use coroutines for async work: `viewModelScope` for UI state, `Dispatchers.IO` for disk/network, and dedicated dispatchers only for real-time audio/video paths.
- For app logging, use `OalLog` or `DiagnosticLog` patterns already used in the target package instead of raw `Log` when possible.
- Add or update unit tests near the island you change when there is an existing test pattern.

## Native / C++ Conventions

- `app/src/main/cpp/` is the JNI aasdk layer. Before editing it, inspect the Kotlin callers and callbacks that consume the native behavior.
- Use C++ style consistent with neighboring files; avoid large abstractions unless they simplify the protocol or lifecycle boundary.
- When touching aasdk channel handling, inspect the matching headers under `external/opencardev-aasdk/include/aasdk/Channel/`.

## Build And Test Commands

- App debug build: `./gradlew :app:assembleDebug`
- App unit tests: `./gradlew :app:testDebugUnitTest`
- Companion debug build: `./gradlew :companion:assembleDebug`
- Companion unit tests: `./gradlew :companion:testDebugUnitTest`
- Full Gradle check when appropriate: `./gradlew test`
- Linux native dependency preparation: `./build_linux.sh --prepare`
- Linux release bundle path: `scripts/linux/bundle-release.sh`
- Windows PowerShell scripts in `scripts/` are the authoritative release workflow; keep Linux `.sh` equivalents in sync if workflow behavior changes.

## Native Dependency Notes

- Linux Gradle builds may automatically run `./build_linux.sh --prepare` before Android `preBuild` tasks.
- Native outputs and caches are expected under `app/src/main/cpp/third_party/`, `/tmp/oal-ndk-deps`, and `/tmp/oal-aasdk-android-build`.
- Do not commit generated native dependency outputs unless a maintainer explicitly asks.
- `secrets/` contains local signing/version material; avoid reading or modifying it unless the task is release/version specific.

## Documentation To Check

- `.github/copilot-instructions.md` — detailed project rules and pitfalls.
- `.github/instructions/app-kotlin.instructions.md` — app-specific Kotlin guidance.
- `.github/instructions/video-pipeline.instructions.md` and `.github/instructions/audio-pipeline.instructions.md` — required context for media changes.
- `docs/architecture.md` — component island architecture.
- `docs/embedded-knowledge.md` — hardware and protocol lessons; read before video/audio/VHAL changes.
- `docs/testing.md` — emulator, VHAL, and remote diagnostics workflows, but verify it matches current direct-mode architecture before following older setup steps.
- `scripts/linux/README.md` — Linux script parity and environment details.

## Safety And Scope

- Do not modify package names, app IDs, signing config, release scripts, or Play publishing behavior unless the task explicitly requires it.
- Do not introduce new network calls or background refresh behavior without a clear user-facing setting or existing pattern.
- Preserve LF line endings for shell scripts.
- Keep changes focused. Do not refactor unrelated islands while fixing app behavior.
- If tests cannot run because native dependencies, Android SDK, or emulator setup is unavailable, state that clearly and still run the narrowest useful static/unit command available.
