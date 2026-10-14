# Hearth Chat

Local chat for Android phones, laptops, and tablets, with no internet or accounts. It works three ways:

- **Wi‑Fi rooms:** any phone can create a room on a shared Wi‑Fi network. Everyone else sees the rooms and chooses which to join.
- **Hotspot rooms:** with no router at all, one phone starts its own hotspot and hosts the room on it. Other phones find it over Bluetooth and join with one tap.
- **Bluetooth chat:** with no Wi‑Fi at all, phones nearby chat directly over Bluetooth, and messages hop from phone to phone.

| Feature | Wi‑Fi or hotspot room | Bluetooth chat |
|---|---|---|
| Group chat | Yes | Yes ("Everyone nearby") |
| Private 1‑to‑1 chat | Yes | Yes, end‑to‑end encrypted |
| Photos, files, voice messages | Yes, files up to 1 GB | Yes, up to 700 KB |
| 1‑to‑1 voice call | Yes | Yes, between two phones in direct range |
| 1‑to‑1 video call | Yes | No |
| Group voice/video calls (30+ people) | Yes | No |
| Laptops and tablets (browser) | Yes | No, Android app only |

Hearth runs on **Android 10 and newer**. The app installs on Android 8 and up, but joining a hotspot room needs Android 10.

## How to use

### Wi‑Fi rooms

1. Connect all devices to the same Wi‑Fi network. Guest networks or router client isolation must be disabled.
2. Install and open Hearth Chat on each Android phone. You see the **Rooms** screen:
   - **Create your own room**: choose **This Wi‑Fi**, give the room a name (or keep the default), and tap **Create room**. This phone hosts it.
   - **Join a room**: every room other phones have created, with who hosts it, how many people are online, and whether a call is live. Tap **Join**.
   - Nobody is put into a room automatically. The only exception: when you reopen the app, it goes back to the room you were last in if that room is still reachable. Tap **Stay here** to stop that.
3. When someone creates a new room, other phones get a notification (**Join** / **See rooms**), and anyone already in a room sees a banner at the top with a Join button.
4. Tap **Rooms** in a room's header to switch rooms. The back button keeps the room running in the background.
5. Tap your name to change how others see you. The Android app uses the same name in every room and in Bluetooth chat.

Each room's messages and files are stored on the phone hosting it, so keep that phone on Wi‑Fi with Hearth running. **Stop hosting** closes the room for everyone; its history stays on that phone for next time.

### Hotspot rooms (no router)

1. On one phone, go to **Your room**, choose **My hotspot**, and tap **Create room**. The phone starts a local hotspot (range about 30–100 m) and hosts the room on it.
2. Other phones see the room under **Join a room**, marked **Hotspot · via Bluetooth**, because Bluetooth finds it. Tap **Join** and allow the Wi‑Fi switch when Android asks.
3. The room then works exactly like a Wi‑Fi room: files, voice and video calls, group calls.

### Laptops and tablets

Laptops and tablets use Hearth in a web browser over Wi‑Fi. They cannot join Bluetooth chat.

1. Put the laptop on the same network as the host phone:
   - **Wi‑Fi room:** use the same Wi‑Fi.
   - **Hotspot room:** connect to the hotspot name and password shown on the host phone's Rooms screen.
2. Open the address shown on the host phone's Rooms screen (or the room's **Connect a laptop** card) in Chrome or Edge.
3. Use the `https://…:8788` address for calls and voice messages. The browser warns once because the phone signs its own certificate, so choose **Advanced → Proceed**.

### Bluetooth chat (no Wi‑Fi)

1. Turn on Bluetooth. On Android 10 and 11, also turn on Location.
2. On the Rooms screen, tap **Bluetooth chat → Open**. When asked, allow **Nearby devices** (Android 12+) or **Location** (Android 10/11). You do not pair phones in Android's Bluetooth settings; Hearth connects them itself.
3. Keep Hearth open on the phones nearby. Within about 10–30 seconds they appear under **People**, and the status line shows how many are nearby.
4. Tap **Everyone nearby** for group chat, or tap a person for private chat and voice calls.
5. Every phone relays messages for the others, so messages reach people out of direct range through the phones in between (up to 7 hops). When two phones meet, they swap the messages from the last 24 hours that the other missed.

If nobody appears, the status line under **Bluetooth chat** says which step is stuck:

| Status line | Meaning |
|---|---|
| `searching…` | This phone cannot find any other Hearth phone. |
| `found 1, connecting… (last try: …)` | It sees another phone, but the connection fails. Hearth keeps retrying. |
| `others can't see this phone (advertising …)` | This phone cannot make itself visible over Bluetooth. |
| `can't search (scan …)` | This phone cannot search over Bluetooth. |

### Calls

- **Group calls (30+ people, Wi‑Fi and hotspot rooms):** tap **Group call** to start one. Everyone in the room sees a banner with **Join voice** / **Join video**, plus an entry in the chat. Everyone can talk.
  - The host phone forwards the voices of the few people speaking at that moment (up to 4).
  - It forwards video from the most recent speakers: 6 tiles, or 4 once more than 10 people are in the call. Screen shares are shown first.
  - Tap the people icon to see everyone in the call. Mute, camera, flip camera, speaker (Android) and screen sharing (laptop) work as in 1‑to‑1 calls.
- **1‑to‑1 voice and video calls (rooms):** use the phone/camera buttons next to someone under "Online now", or **Call**. These go directly between the two devices.
- **Bluetooth voice calls:** open a person in Bluetooth chat and tap the call button. Both phones must be in direct Bluetooth range; calls do not hop through other phones.
- Group calls need Chrome, Edge or the Hearth Android app (they use WebCodecs). Firefox and Safari can chat and make 1‑to‑1 calls.

## Build the Android app

Open this folder in Android Studio and build the `android` application, or run:

```sh
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`. A copy of the latest build is kept as `HearthChat.apk` in this folder.

## Current limits

- Each room is hosted by one Android phone, and a phone hosts at most one room. Other devices must be able to reach the host over Wi‑Fi. Guest networks, router client isolation, or blocked multicast discovery can stop rooms from showing up.
- No accounts. Calls are encrypted between devices (WebRTC), and private Bluetooth messages are end‑to‑end encrypted. Room chat messages and files, and Bluetooth "Everyone nearby" messages, are not end‑to‑end encrypted. Use Hearth only with people you trust.
- To receive a call in a room, Hearth must be open on that device (in the foreground on Android).
- Group calls run through the host phone. Large calls work best when the host phone is on 5 GHz Wi‑Fi and plugged in; with 30 people and video on, it forwards roughly 20–30 Mbit/s.
- Install version 4.0.2 on every phone. Phones on 3.x cannot use Bluetooth chat or hotspot rooms.
- Shared files in rooms are limited to 1 GB each. Browsers keep offline copies only of photos and videos up to 25 MB; other files are downloaded from the host phone when needed.
- Documents, archives, APKs and other non-media files are always served as downloads, never opened inside the chat page.
- A single room message can be up to 10 MB, to keep transfers and phone storage reliable.
- Bluetooth chat:
  - Text messages up to 20,000 characters; photos, files and voice messages up to 700 KB.
  - No video calls and no group calls.
  - Range is roughly 10–30 m per hop indoors.
  - A message sent while nobody is in range waits on your phone. If the app is closed before someone comes in range, it is not sent.
- On phones whose **Android System WebView** is out of date (common on Android 10/11 phones that never update it), screens may look slightly cramped and group calls are unavailable. Update **Android System WebView** from the Play Store.
- Keep the host phone connected and Hearth Chat running for a room to stay available. Android may stop background work if Hearth Chat is force-stopped or the system restricts the app; opening the app again restarts discovery.
