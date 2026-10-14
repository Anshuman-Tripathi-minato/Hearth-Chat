# Hearth Chat

Local chat rooms for Android phones, laptops, and tablets on one Wi-Fi network. Any phone can create a room; everyone else sees the rooms on the Wi‑Fi and chooses which to join. Rooms support chat, files, voice messages, 1‑to‑1 calls and group voice/video calls for 30+ people, with no internet or accounts.

## How to use

1. Connect all devices to the same Wi‑Fi network. Guest networks or router client isolation must be disabled.
2. Install and open Hearth Chat on each Android phone. You see the **Rooms** screen:
   - **Create your own room**: this phone hosts it. Give it a name, or keep the default.
   - **Rooms on this Wi‑Fi**: every room other phones have created, with who hosts it, how many people are online, and whether a call is live. Tap **Join**.
   - Nobody is put into a room automatically. The only exception: when you reopen the app, it goes back to the room you were last in if that room is still on the Wi‑Fi. Tap **Stay here** to stop that.
3. When someone creates a new room, other phones get a notification (**Join** / **See rooms**), and anyone already in a room sees a banner at the top with a Join button.
4. Tap **Rooms** in a room's header to switch rooms. The back button keeps the room running in the background.
5. Laptops and tablets: open the address shown on the host phone's Rooms screen (or the room's "Connect a laptop" card) in a browser. Use the `https://…:8788` address for calls and voice messages: the browser warns once because the phone signs its own certificate, so choose **Advanced → Proceed**.
6. Tap your name to change how others see you. The Android app uses the same name in every room.

Each room's messages and files are stored on the phone hosting it, so keep that phone on Wi‑Fi with Hearth running. **Stop hosting** closes the room for everyone; its history stays on that phone for next time.

### Calls

- **Group calls (30+ people):** tap **Group call** to start one. Everyone in the room sees a banner with **Join voice** / **Join video**, plus an entry in the chat. Everyone can talk; the host phone forwards the voices of the few people speaking at that moment (up to 4) and video from the most recent speakers (6 tiles, or 4 once more than 10 people are in the call), with screen shares shown first. Tap the people icon to see everyone in the call. Mute, camera, flip camera, speaker (Android) and screen sharing (laptop) work as in 1‑to‑1 calls.
- **1‑to‑1 voice and video calls:** use the phone/camera buttons next to someone under "Online now", or **Call**. These go directly between the two devices.
- Group calls need Chrome, Edge or the Hearth Android app (they use WebCodecs). Firefox and Safari can chat and make 1‑to‑1 calls.

## Build the Android app

Open this folder in Android Studio and build the `android` application, or run:

```sh
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## Current limits

- Each room is hosted by one Android phone, and a phone hosts at most one room. Other devices must be able to reach the host on the same Wi-Fi. Guest networks, router client isolation, or blocked multicast discovery can prevent rooms from showing up.
- This version uses one shared home room, with no accounts. Calls are encrypted between devices (WebRTC), but chat messages and files are not end-to-end encrypted. Use it only on a trusted home network.
- To receive a call, Hearth must be open on that device (in the foreground on Android).
- Group calls run through the host phone. Large calls work best when the host phone is on 5 GHz Wi‑Fi and plugged in; with 30 people and video on, it forwards roughly 20–30 Mbit/s.
- Install version 3.0 on every phone. Older versions still auto-join rooms and cannot create named rooms or join group calls.
- Shared files are limited to 1 GB each. Browsers keep offline copies only of photos and videos up to 25 MB; other files are downloaded from the host phone when needed.
- Documents, archives, APKs and other non-media files are always served as downloads, never opened inside the chat page.
- There is no 5,000-character input cap. A single message can be up to 10 MB to keep transfers and phone storage reliable.
- Keep the host phone connected and Hearth Chat running for the room to stay available. Android may stop background work if Hearth Chat is force-stopped or the system restricts the app; opening the app again restarts discovery.
