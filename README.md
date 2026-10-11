# Hearth Chat

A local chat room for Android phones, laptops, and tablets on one Wi-Fi network. Hearth Chat phones discover each other automatically and elect one phone to host the shared room. The other phones open that room in the app; a laptop joins with the room address shown in the Android notification or in the chat's “Connect a laptop” card.

## How to use

1. Connect all devices to the same Wi-Fi network. Guest networks or router client isolation must be disabled.
2. Install and open Hearth Chat on each Android phone. The phones discover the shared room automatically; one becomes the host and the others join it.
3. Open the address shown in any phone's ongoing notification on the laptop or tablet. It looks like `http://192.168.x.x:8787`. A phone already in the room also shows this address in the “Connect a laptop” card.
4. Use the name button in the top right to set the name shown beside your messages.

The app starts room discovery when it opens and restarts after the phone reboots. Android shows whether this phone hosts or joined the room, plus its address. Messages and media are stored on the elected host phone, so keep that phone powered on and connected to Wi-Fi. Other Android phones and browser clients use the same room and see the same history. A browser also saves messages it receives locally and catches up when it reconnects.

Long-press a message to copy its text. Long-press a shared photo or video to download it; APK downloads are saved to the phone's Downloads folder. The Android app opens the system photo/video picker when you attach media.

## Build the Android app

Open this folder in Android Studio and build the `android` application, or run:

```sh
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## Current limits

- One Android phone hosts the room; the other phones discover and join it. Other devices must be able to reach the host on the same Wi-Fi. Guest networks, router client isolation, or blocked multicast discovery can prevent joining.
- This first version uses one shared home room, with no accounts or end-to-end encryption. Use it only on a trusted home network.
- Shared photos and videos are limited to 120 MB per file.
- There is no 5,000-character input cap. A single message can be up to 10 MB to keep transfers and phone storage reliable.
- Keep the host phone connected and Hearth Chat running for the room to stay available. Android may stop background work if Hearth Chat is force-stopped or the system restricts the app; opening the app again restarts discovery.
