# Bridge

The C++ headless binary that bridges a phone's Android Auto session to the car app over TCP, using the OAL protocol.

## Architecture

- **OAL Protocol**: 3 TCP channels — control (5288, JSON lines), audio (5289, binary), video (5290, binary)
- **aasdk v1.6**: Phone ↔ bridge communication via Android Auto protocol
- **SCO Audio**: BT HFP phone call audio via Bluetooth SCO sockets

### Key Source Files
- `headless/include/openautolink/oal_protocol.hpp` — OAL wire format (video/audio headers)
- `headless/include/openautolink/oal_session.hpp` — OAL session state machine
- `headless/include/openautolink/tcp_car_transport.hpp` — TCP server for car app
- `headless/src/live_session.cpp` — aasdk integration, service handlers
- `headless/src/main.cpp` — CLI entry point
- `scripts/aa_bt_all.py` — BT/WiFi pairing service

## Display Safe-Area Insets

Android Auto renders a fixed-resolution video frame, but physical car displays often have non-rectangular bezels — curved edges, tapered corners, or cutouts that obscure content near the edges. The bridge can tell the phone where interactive UI (buttons, cards, text) should be placed within that frame, while still allowing background content (maps, album art) to fill the entire frame.

This is configured via **stable insets** in `/etc/openautolink.env`:

```bash
# Format: top,bottom,left,right (in video-coordinate pixels)
OAL_AA_INIT_STABLE_INSETS=0,0,0,110
```

**Stable insets** = "render background content (maps) here, but keep interactive UI in the safe area."
**Content insets** = "don't render anything here at all" (hard black cutoff).

The default `right=110` is tuned for the **2024 Chevrolet Blazer EV**, which has a curved/tapered right bezel that clips ~150 physical pixels. Adjust for your vehicle's display, or set to blank to disable.

See the comments in [sbc/openautolink.env](sbc/openautolink.env) for all available knobs.
