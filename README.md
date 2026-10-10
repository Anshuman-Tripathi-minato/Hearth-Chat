# Hearth Chat

A local chat room for devices on one home Wi-Fi network. The Android app keeps the room available and stores chat history and shared files on the phone. A laptop joins by opening the phone's room address in a browser.

## How to use

1. Install and open Hearth Chat on the Android phone that will host the room.
2. Keep the app in the background. Its ongoing notification shows the room address.
3. On a laptop or tablet on the same Wi-Fi, open `http://PHONE-IP:8787` in a browser. The address is also shown in the chat's “Connect a laptop” card.
4. Use the name button in the top right to set the name shown beside your messages.

The app starts its local room service when it opens and restarts that service after the phone reboots. Android shows an ongoing notification while the room is available. Messages and media are stored in the app's private phone storage. A browser saves messages it receives locally and catches up when it reconnects. Keep a browser tab open if you need to read its saved messages while the phone is offline.

## Build the Android app

Open this folder in Android Studio and build the `android` application, or run:

```sh
./gradlew :android:assembleDebug
```

The debug APK is written to `android/build/outputs/apk/debug/android-debug.apk`.

## Current limits

- The Android phone is the room host; other devices must be able to reach it on the same Wi-Fi. Guest networks and router client isolation can block this.
- This first version uses one shared home room, with no accounts or end-to-end encryption. Use it only on a trusted home network.
- Shared photos and videos are limited to 120 MB per file.
- Android may stop background work if Hearth Chat is force-stopped or the system restricts the app. Opening it again starts the room service.
