# Privacy Policy — OpenAutoLink

**Last updated:** May 3, 2026

## Overview

OpenAutoLink is an Android Automotive OS (AAOS) app plus phone companion app that wirelessly projects Android Auto from your phone onto your car's infotainment screen over a local network connection. This privacy policy explains what data the apps access, how it is used, and how it is protected.

## Data Collection

**OpenAutoLink does not collect, store, transmit, or share any personal data with the developer, any third party, or any remote server.** Projection operates locally between your car's head unit and your phone companion app. No projection data leaves your local car/phone network because of OpenAutoLink.

## Permissions and Their Purpose

### Microphone (`RECORD_AUDIO`)
The AAOS app captures audio from the car's built-in microphone to enable hands-free voice commands (e.g., "Hey Google") and phone calls through Android Auto. Microphone audio is sent only to your phone's Android Auto session over the local OpenAutoLink connection. **Audio is never recorded, stored, or transmitted to the developer or an OpenAutoLink server.**

### Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)
The AAOS app reads GPS location from the car's built-in GNSS receiver. This data is forwarded to your phone's Android Auto session to improve navigation accuracy (e.g., in tunnels or urban canyons where the phone's GPS signal is weak). **Location data is never stored by OpenAutoLink or transmitted to an OpenAutoLink server.**

### Network (`INTERNET`, `ACCESS_NETWORK_STATE`)
The AAOS app connects to the phone companion app over your local Wi-Fi network using TCP. The companion listens for the car on TCP port `5277`, publishes local discovery information with mDNS, and answers local identity probes on TCP `5278` and UDP `5279`. **Projection does not require OpenAutoLink internet connections to external servers.**

### Vehicle Data (`CAR_SPEED`, `CAR_ENERGY`, `CAR_POWERTRAIN`, `CAR_EXTERIOR_ENVIRONMENT`, `CAR_INFO`)
The AAOS app reads vehicle sensor data (speed, gear, battery level, temperature, etc.) from the car's Vehicle HAL to forward to your phone's Android Auto session. This enables the phone to display accurate vehicle information. **Vehicle data is sent only to your phone over the local projection connection and is not stored by OpenAutoLink.**

### Foreground Service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`)
Required to keep audio playback and the projection session active while the AAOS app is in use. The phone companion also uses a foreground service and visible notification while it is waiting for or relaying a car connection.

### Car App Permissions (`NAVIGATION_TEMPLATES`, `ACCESS_SURFACE`)
Required by the Android Automotive Car App Library to render navigation information on the instrument cluster and access the display surface for video rendering.

## Sensor Data

The AAOS app reads accelerometer, gyroscope, and magnetic field sensors from the car's head unit hardware to provide inertial measurement data to Android Auto. This improves navigation accuracy during GPS signal loss (tunnels, parking garages). This sensor data is sent only to your phone over the local projection connection.

## Data Storage

The AAOS app stores only user preferences locally on the device using Android DataStore:
- Connection settings (transport mode, manual IP when enabled, preferred phone identity)
- Video/audio configuration (codec, resolution, FPS)
- Display preferences

The phone companion stores its service settings locally. Car Wi-Fi SSID/password entries are stored in app-private, non-backed-up preferences used only to request the configured car Wi-Fi network. The companion also stores a random `phone_id` and a friendly name so the car can distinguish multiple phones.

No personal data, location history, audio recordings, or vehicle data is stored by OpenAutoLink.

## Third-Party Services

OpenAutoLink does not integrate with any third-party analytics, advertising, crash reporting, or tracking services.

## Data Security

Projection communication occurs over the local network between your car and phone. The phone companion accepts local connections while its foreground service is running. The AAOS app does not expose projection services; its optional remote log server is available only when explicitly started from diagnostics.

## Children's Privacy

OpenAutoLink does not collect any data from any users, including children.

## Changes to This Policy

If this privacy policy is updated, the new version will be published in the app's GitHub repository and the "Last updated" date will be revised.

## Contact

For questions about this privacy policy, open an issue on the project's GitHub repository:
https://github.com/mossyhub/openautolink
