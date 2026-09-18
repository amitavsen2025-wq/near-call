# NearCall V2 — Offline Mesh Prototype

Android/Kotlin prototype for an internet-free nearby community network.

## What V2 includes
- Google Nearby Connections `P2P_CLUSTER` for offline device-to-device links.
- Bluetooth/Wi-Fi transports are selected by Nearby Connections; mobile internet is not used by the app's communication layer.
- Multiple simultaneous nearby connections.
- Application-level mesh topology announcements.
- Multi-hop routing using a simple BFS route table.
- TTL + duplicate suppression to prevent endless packet loops.
- Offline text chat routed through other phones.
- 1-to-1 app voice calling prototype using 8 kHz μ-law audio packets.
- Incoming call dialog, answer/decline, and call end.
- Local persistent anonymous node ID; no phone number required.

## Important limitations
1. This is a prototype, not a production telecom system.
2. A guaranteed 1 km range cannot be promised by software. It depends on phone hardware, obstacles, radio conditions, and whether enough relay phones form a connected path.
3. Voice is intentionally low-bandwidth and unoptimized. For production, use Opus/WebRTC-style audio, jitter buffering, congestion control, authentication, and stronger routing.
4. Android background execution and battery restrictions can interrupt mesh relaying. A production version should use a foreground service with clear user consent.
5. Nearby Connections requires Google Play services on supported devices. The app itself does not need mobile data while communicating, but Android Studio must download Gradle/dependencies during the initial build.
6. The manifest contains INTERNET because Android/Google Play services may require it for dependency/runtime services; the app's chat/call packets are sent through Nearby Connections, not a remote server.
7. For a real 30-user deployment, routing, group voice, security, rate limits, battery management, and reconnection logic need further engineering and testing on the target phones.

## Build
1. Install Android Studio (current stable version).
2. Open the `NearCallV2` folder.
3. Let Gradle sync/download dependencies.
4. Enable Developer Options + USB debugging on two or more Android phones.
5. Install the app on each phone.
6. Grant Nearby/Bluetooth and microphone permissions.
7. Tap **Start / Refresh Mesh** on each phone.
8. Keep phones reasonably close for the first test. Once they discover one another, test text, then voice.

## Suggested test sequence
A ↔ B: text
A ↔ B: voice
A ↔ B ↔ C: text relay
A ↔ B ↔ C: voice relay
Then test with 5–10 phones and gradually increase the number.

## Production roadmap
- Proper identity/authentication and community invite codes
- Encrypted end-to-end messaging with key exchange
- Opus audio codec + jitter buffer
- Foreground mesh service
- Better route discovery (sequence numbers, link metrics, route expiry)
- Store-and-forward messages
- Group chat and group voice
- Emergency broadcast
- Battery-aware relay mode
- Abuse/spam controls
- Extensive RF/range testing
