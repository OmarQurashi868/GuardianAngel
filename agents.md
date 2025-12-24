# GuardianAngel agents and architecture

This document explains the architecture of the GuardianAngel project, the “agents” involved and how they collaborate, the refactor standards applied, and how to extend the system safely.

The system implements a simple LAN intercom with two roles:
- Guardian: listens to audio broadcast from a ward and can push-to-talk (PTT) to the ward.
- Ward: captures microphone audio and broadcasts it to connected guardians; receives PTT audio from a guardian and plays it locally.

The project uses Jetpack Compose for UI and a foreground service to keep streaming alive when backgrounded.

## Top-level layout

- `app/src/main/java/com/example/guardianangel/MainActivity.kt`
  - App entry point; orchestrates UI navigation and all runtime behaviors (network, audio, permissions, notifications).
- `app/src/main/java/com/example/guardianangel/model/Screen.kt`
  - Screen navigation model.
- `app/src/main/java/com/example/guardianangel/model/GuardianConnection.kt`
  - Data model for an attached guardian client.
- `app/src/main/java/com/example/guardianangel/core/Constants.kt`
  - Centralized constants for ports, audio config, and notification IDs/channel names.
- `app/src/main/java/com/example/guardianangel/service/ForegroundService.kt`
  - Foreground service that anchors long-running work with a persistent notification.
- `app/src/main/java/com/example/guardianangel/ui/screens/*.kt`
  - Compose screens split by role and state:
  - `MainScreen`, `WardScreen`, `GuardianScreen`, `GuardianConnectedScreen`.
- `app/src/main/res/*`
  - Manifest, resources, themes.

## Agents

Think of each functional concern as an agent (a cohesive unit with clear responsibilities). These run inside the app process and collaborate via calls and shared state.

1) Ward Agent (server/broadcaster)
- Role: Capture device microphone PCM and broadcast to all connected guardians; host PTT receiver.
- Network:
  - TCP server on `Constants.AUDIO_PORT` for continuous audio broadcast.
  - TCP server on `Constants.PTT_PORT` for PTT audio uplink from guardian.
  - Registers itself with NSD as `_ward._tcp` named `ward_<device model>`.
- Audio:
  - Capture with `AudioRecord` using 44.1kHz mono PCM 16-bit.
  - Applies a safety-limited gain before broadcasting.
  - Plays back PTT audio via `AudioTrack` when a guardian PTT socket is connected; mutes its own broadcast capture while receiving PTT to prevent feedback.
- Lifecycle:
  - Started from `WardScreen` (via `MainActivity.startBroadcasting()`).
  - Cleans up on exiting the screen or when stopped: closes sockets, stops audio capture and playback, unregisters NSD.

2) Guardian Agent (client/receiver)
- Role: Discover available wards, connect to one, play the ward’s PCM stream, and provide push-to-talk audio back to the ward.
- Network:
  - Discovers `_ward._tcp` via NSD and resolves service host addresses.
  - Connects to ward on `Constants.AUDIO_PORT` and sends its device name as a greeting string.
  - When PTT is engaged, opens a separate socket to the ward’s `Constants.PTT_PORT` to transmit MIC audio.
- Audio:
  - Plays incoming PCM via `AudioTrack` with adjustable volume.
  - PTT uses `AudioRecord` on MIC and streams to ward.
- Resilience:
  - Monitors incoming stream activity and raises an alert notification if no data is received for more than `Constants.CONNECTION_TIMEOUT_MS`.

3) NSD Agent (discovery/registration)
- Role: DNS-SD service discovery and registration for `_ward._tcp`.
- Ward side: Registers service and keeps it available while streaming.
- Guardian side: Manages `resolveService` queue to avoid concurrent resolves and maintains a list of discovered services by display name.

4) Foreground Service Agent (keep-alive/OS integration)
- Role: Pins a foreground notification during long-running activities to avoid OS killing the process.
- Notification channel management and flagging as media playback (via reflective calls for compatibility).

5) Audio Agent (capture/playback primitives)
- Role: Encapsulated behaviors managed by `MainActivity`:
  - `AudioRecord` setup and lifecycle.
  - `AudioTrack` setup and lifecycle.
  - Gain application and clamping to avoid clipping artifacts.

6) Notifications Agent (user alerts)
- Role: Creates and manages two channels:
  - Service channel: low-priority persistent notification for streaming.
  - Alerts channel: high-importance call-like notification for connection loss. Attempts to bypass DND and use full-screen intent where available.
- Uses reflective calls on channels and startForeground to maximize backward compatibility with newer behaviors.

## Runtime flows

1) Ward broadcast
- User opens `WardScreen`.
- `MainActivity.startBroadcasting()`:
  - Starts Foreground Service with “Ward Active”.
  - Registers NSD service `_ward._tcp`.
  - Opens server socket on `AUDIO_PORT` and accepts guardian clients.
  - If capture not running, initializes `AudioRecord` and begins MIC capture.
  - For each audio buffer:
    - Applies gain and broadcasts to all connected guardian sockets (skips when PTT is being received).
  - Monitors client sockets; removes disconnected guardians and cleans resources.

2) Guardian discovery and connection
- User opens `GuardianScreen`.
- `MainActivity.startDiscovery()` begins NSD discovery and queued resolving of services.
- User selects a service or enters an IP.
- `MainActivity.connectToWardByIp()`:
  - Foreground Service “Guardian Active”.
  - Creates socket to ward `AUDIO_PORT`, writes guardian device name.
  - Switches to `GuardianConnectedScreen`.
  - `startAudioPlayback()` reads PCM and writes into `AudioTrack`. Updates last data timestamp each buffer; if stalled > timeout, shows alert.

3) Guardian push-to-talk
- On `GuardianConnectedScreen`, hold PTT control:
  - `startPushToTalk()` opens socket to ward `PTT_PORT`.
  - Captures MIC via `AudioRecord` and streams to ward.
- Release PTT control:
  - `stopPushToTalk()` closes socket and releases audio resources.

4) Stop/cleanup
- On Back or screen exit:
  - Ward: stop broadcast, close server sockets, unregister NSD, clear guardian list, stop Foreground Service.
  - Guardian: stop playback, stop PTT, stop discovery, close guardian socket, stop Foreground Service.

## Packaging and naming standards

- Package-by-feature and responsibility:
  - `model`: UI state and data models (`Screen`, `GuardianConnection`).
  - `core`: application-wide constants (`Constants`).
  - `service`: Android service(s) (`ForegroundService`).
  - `ui/screens`: one Compose screen per file.
- Constants:
  - Centralize all “magic numbers” and identifiers in `core/Constants`.
  - Keep ports, sample rate, channel config, notification channel IDs in one place.
- Screen navigation:
  - `model/Screen` sealed hierarchy for app screens.
- File naming:
  - One top-level class or composable per file; match file name to type for findability.
- Logging:
  - Use `Constants.TAG` for log tag consistency.

## Compose/UI standards

- Each screen is a pure composable in `ui/screens`, receiving only what it needs:
  - `MainScreen`: two role entrypoints.
  - `WardScreen`: shows local IP and connected guardians.
  - `GuardianScreen`: lists discovered wards and manual connect.
  - `GuardianConnectedScreen`: displays connected ward, volume, PTT, and connection status.
- Imperative side-effects (network start/stop) are triggered from `MainActivity` in direct response to screen transitions or user events. The screens do not directly own system resources and only invoke provided callbacks or, temporarily, call activity functions where necessary.

Future improvement:
- Introduce `ViewModel`s per screen and lift side-effects to view models using Kotlin Coroutines and Flows to avoid directly referencing the activity.

## Threading and resource management

- Long-running or IO work runs in background threads created with `thread {}`.
- Sockets and audio tracks/records are closed and released in `finally` blocks to reduce resource leaks.
- Minimal sleep intervals are used where needed to allow background threads to unwind before releasing components.
- Connection monitoring uses non-blocking read attempts and timeouts to detect dead connections.

Future improvement:
- Replace raw threads and sleeps with coroutines (`Dispatchers.IO`, structured concurrency, `withContext`) and channels/flows for backpressure and cancellation.
- Replace manual connection monitor with read loop metrics and `withTimeout` coroutines.

## Permissions and OS integration

- Microphone and network permissions are required; notification permission requested on Android 13+.
- Foreground service runs with media playback type where available for better survivability while streaming.
- Notification channels are created via reflection for compatibility across API levels while enabling sound and DND bypass on the alert channel.

## Security and constraints

- Unencrypted PCM over LAN TCP sockets.
- No authentication or pairing. Anyone on the same network can connect if they know the IP/ports or discover via NSD.
- Production hardening suggestions:
  - TLS on both `AUDIO_PORT` and `PTT_PORT`.
  - Shared secret or mDNS TXT records with a pairing token.
  - Optional SRTP or Opus encoding to reduce bandwidth and add resilience.

## Extending the system

Add a new agent
- Create a package for the responsibility (for example `network`, `audio`, or `domain`).
- Isolate external side effects (sockets, audio) behind a small interface for testing.
- Keep constants in `core/Constants`; avoid scattering ports, sample rates, and channel configs.

Add codecs or compression
- Introduce an `AudioProcessor` or `Codec` abstraction with `encode(buffer: ByteArray): ByteArray` and `decode(buffer: ByteArray): ByteArray`.
- Place implementations under `audio/codec`.
- Wire the encoder in the Ward Agent before broadcasting; wire the decoder in the Guardian Agent before playback.

Add encryption
- Add `CipherStream` wrappers for sockets (e.g., TLS or symmetric crypto).
- Derive keys out of band or via pairing flow; store securely with `EncryptedSharedPreferences` or the keystore.

Add a new screen
- Add a composable in `ui/screens`, expose only required callbacks/inputs.
- Add a navigation entry to `model/Screen`.
- Handle transitions and side-effects in `MainActivity` (or ideally a `ViewModel`).

Add analytics or logs
- Use a single logging entrypoint. Keep PII out of logs.
- Consider structured logs for connection events.

## Known limitations and future work

- `MainActivity` still orchestrates most IO and system interactions. Next step: move networking and audio into dedicated classes and expose state via coroutines and Flows to the UI layer.
- Use DI (e.g., Hilt) to provide agents (NSD, audio, sockets) and enable testability.
- Handle audio focus, noisy intents (e.g., unplug headphones), and Bluetooth routing.
- Consider Opus or AAC to reduce bandwidth and improve quality.
- Consider exponential backoff and retry strategies for reconnection and discovery recovery.

## Operational runbook

- Guardian cannot find Ward
  - Ensure both devices are on the same LAN.
  - Verify Wi‑Fi multicast is allowed on the network.
  - Try manual IP connect from Guardian.
- Audio too quiet or distorted
  - Adjust volume on Guardian.
  - The Ward applies a fixed gain; extreme environments may still clip; consider codec/AGC.
- Frequent disconnect alerts
  - Check Wi‑Fi stability and background restrictions.
  - The Guardian will raise an alert if no audio data arrives for more than the configured timeout.

## Glossary

- Guardian: The listening device; receives broadcast audio and can PTT to the Ward.
- Ward: The broadcasting device; captures and streams microphone audio to guardians; receives PTT audio.
- PTT (Push-to-Talk): Unidirectional speech from Guardian to Ward while the control is held.
- NSD (Network Service Discovery): Android’s DNS-SD/mDNS implementation used here to discover Ward services.

## Conventions checklist

- Use `Constants` for all ports, audio config, and notification identifiers.
- Keep one responsibility per file. Name files after the main type they contain.
- Compose screens are stateless UI where possible; side-effects are triggered by activity or view model.
- Always release sockets and audio objects in `finally`.
- Ask for runtime permissions before starting microphone capture.
- Update the Foreground Service when starting long-running work; stop it when done.
