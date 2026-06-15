# OpenAutoLink .NET AAOS Shell

Phase-1 scaffold for a Rider-friendly `.NET for Android` AAOS shell.

## Projects

- `OpenAutoLink.CarApp` — Android Automotive shell app
- `OpenAutoLink.Core` — shared shell/session/discovery scaffolding
- `OpenAutoLink.Native` — future native shim (`oal_shim`)

## Current scope

- Launch a basic AAOS activity in landscape
- Keep screen on
- Show shell status in a simple layout
- Provide placeholders for discovery, connect, and native session start
- Prepare for a future `oal_shim` native library

## Prerequisites

1. Install the Android workload:
   - `dotnet workload install android`
2. Install Android SDK + emulator images.
3. Create an **x86_64 Android Automotive emulator** first.

## Rider setup

1. Open `dotnet/OpenAutoLink.sln` in Rider **as the project/solution**.
   - Do **not** open the repository root when debugging the .NET shell.
   - This repo root already contains Gradle Android project metadata under `.idea/`, which can confuse Rider's Android deploy/debug integration for the .NET app.
2. Ensure Rider sees the Android SDK and emulator.
3. Set `OpenAutoLink.CarApp` as the startup project.
4. Create or use Rider's auto-generated **Android** run configuration:
   - Startup project: `OpenAutoLink.CarApp`
   - Deploy: `Default APK`
   - Target: `Emulator`
   - Emulator name: choose your Automotive x86_64 AVD
5. Start debugging.

### If Rider shows "Unable to evaluate deployment properties"

1. Open **Run | Edit Configurations**.
2. Delete any broken config for `OpenAutoLink.CarApp`.
3. Create a new **Android** configuration, not a plain **.NET Project** configuration.
4. Set:
   - **Startup project**: `OpenAutoLink.CarApp`
   - **Deploy**: `Default APK`
   - **Target**: `Emulator`
   - **Name**: your running Automotive emulator
5. Apply and debug again.

The project pins Debug emulator deploys to `android-x64` so Rider has a concrete runtime identifier to evaluate.

`OpenAutoLink.Core` intentionally targets plain `.NET` instead of Android so Rider only treats `OpenAutoLink.CarApp` as the deployable Android project.

If Rider still fails while the app is already installed on the emulator, the most common cause is opening the **repo root** instead of the standalone `dotnet/OpenAutoLink.sln` solution.

Rider can launch/debug Android projects with its Android run configuration and can target a named emulator directly.

## Native shim plan

The app currently probes for `liboal_shim.so`.

Next step:

- implement `OpenAutoLink.Native/src/oal_shim.cpp`
- package `liboal_shim.so` for `x86_64`
- wire session create/start/stop callbacks through `NativeMethods`
