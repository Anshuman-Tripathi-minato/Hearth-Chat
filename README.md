# Hearth Chat

A local chat room for Android phones, laptops, and tablets on one Wi-Fi network. Hearth Chat phones discover each other automatically and elect one phone to host the shared room. The other phones open that room in the app; a laptop joins with the room address shown in the Android notification or in the chat's “Connect a laptop” card.

## How to use

1. Connect all devices to the same Wi-Fi network. Guest networks or router client isolation must be disabled.
2. Install and open Hearth Chat on each Android phone. The phones discover the shared room automatically; one becomes the host and the others join it.
3. Open the address shown in any phone's ongoing notification on the laptop or tablet. It looks like `http://192.168.x.x:8787`. A phone already in the room also shows this address in the “Connect a laptop” card.
4. Use the name button in the top right to set the name shown beside your messages.

The app starts room discovery when it opens and restarts after the phone reboots. Android shows whether this phone hosts or joined the room, plus its address. Messages and media are stored on the elected host phone, so keep that phone powered on and connected to Wi-Fi. Other Android phones and browser clients use the same room and see the same history. A browser also saves messages it receives locally and catches up when it reconnects.

Share any kind of file: photos, videos, audio, PDFs, Word/Excel/PowerPoint documents, ZIP/RAR/7z archives, APKs and more. Tap the paperclip to choose photos & videos, documents, or any file; you can pick several at once, and on a laptop you can also drag files onto the chat or paste them. Each file shows a type card with its size and a download button, and an upload progress bar while sending. The **Files** button opens everything shared in the room, split into files and photos & videos. Use **Search** to find messages or file names.

**Voice messages:** tap the microphone (shown when the message box is empty), talk, then tap send, or the bin to discard. Voice notes play inline with a waveform, seek and 1×/1.5×/2× speed.

**Voice and video calls:** tap **Call** (or the phone/camera buttons next to someone under "Online now") to call any device that has Hearth open. Calls go directly between the two devices over your Wi‑Fi. During a call you can mute, turn the camera off, flip between front and back cameras on phones, switch speakerphone in the Android app, and share your screen from a laptop. Each call leaves an entry in the chat (answered with duration, missed, declined), with a **Call back** button.

**Chat extras:** reply to a message, react with an emoji (or double-click to ❤️), delete your own messages for everyone, see who is online and who is typing, and jump back to the latest message.

**Secure address for calls (laptops):** browsers only allow the microphone and camera on secure pages, so the host phone also serves the room at `https://192.168.x.x:8788`. The phone signs its own certificate, so a laptop browser warns the first time: choose **Advanced → Proceed**. The Android app handles this automatically. The plain `http://…:8787` address still works for chat and files.

Long-press (or right-click) a message to copy its text, download its file, or copy a file link. Tap a photo to view it full screen. On Android, downloads are saved to the phone's Downloads folder; tap the finished download notification to open it (an APK opens the system installer).

## Build the Android app

Open this folder in Android Studio and build the `android` application, or run:

```sh
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## Current limits

- One Android phone hosts the room; the other phones discover and join it. Other devices must be able to reach the host on the same Wi-Fi. Guest networks, router client isolation, or blocked multicast discovery can prevent joining.
- This version uses one shared home room, with no accounts. Calls are encrypted between devices (WebRTC), but chat messages and files are not end-to-end encrypted. Use it only on a trusted home network.
- Calls are one-to-one. To receive a call, Hearth must be open on that device (in the foreground on Android).
- The phone hosting the room must run Hearth 2.0 for calls, voice messages, reactions and replies; update every phone so any of them can become host.
- Shared files are limited to 1 GB each. Browsers keep offline copies only of photos and videos up to 25 MB; other files are downloaded from the host phone when needed.
- Documents, archives, APKs and other non-media files are always served as downloads, never opened inside the chat page.
- There is no 5,000-character input cap. A single message can be up to 10 MB to keep transfers and phone storage reliable.
- Keep the host phone connected and Hearth Chat running for the room to stay available. Android may stop background work if Hearth Chat is force-stopped or the system restricts the app; opening the app again restarts discovery.
