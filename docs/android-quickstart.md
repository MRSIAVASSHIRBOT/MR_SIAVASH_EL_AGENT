# Android companion quick start

## Requirements
- Android 8.0 (API 26) or newer.
- Android Studio with JDK 17, or Gradle 8.9 with Android SDK 35.
- A Hub deployment and owner account only if you want optional Hub pairing/heartbeat. Local actions and the text assistant do not require Hub. Do not put the owner's bearer token in the Android app.

## Build
From the repository root:

```bash
gradle --no-daemon :android:assembleDebug
```

Install the debug APK on your own Android device using Android Studio or:

```bash
adb install -r android/build/outputs/apk/debug/android-debug.apk
```

The GitHub Android workflow also builds the debug APK and publishes it as a workflow artifact after a successful build.

## Pairing
1. Open MR SIAVASH EL AGENT on Android and copy the displayed Device ID.
2. Sign in to your Hub owner account and obtain an owner access token through the normal Hub login flow.
3. Create a one-time pairing challenge from the owner-authenticated Hub API. Use the Android Device ID shown in the app:

```bash
curl --request POST "https://YOUR-HUB-ORIGIN/devices/pairing-challenges" \
  --header "Authorization: Bearer YOUR_OWNER_ACCESS_TOKEN" \
  --header "Content-Type: application/json" \
  --data '{
    "device_id": "ANDROID_DEVICE_UUID",
    "platform": "android",
    "label": "My Android phone",
    "advertised_capabilities": ["device.heartbeat"],
    "scopes": ["device.heartbeat"]
  }'
```

4. Copy the returned one-time `pairing_code` into the Android app, enter the same HTTPS Hub origin, and tap **Pair with Hub**.
5. The app exchanges the code directly with Hub. The code is not stored. Device credentials are encrypted using an AES-GCM key held by Android Keystore.
6. Tap **Send heartbeat now** to verify connectivity. WorkManager schedules a network-constrained periodic heartbeat; Android controls the exact execution time to preserve battery.

The owner chooses the scopes in the pairing challenge. Device-advertised capabilities do not grant permissions. For this preview, request only `device.heartbeat`; do not grant future scopes before the corresponding Hub endpoint and Android behavior are released and reviewed.

## Local actions
- **Open ChatGPT** opens the installed ChatGPT Android app when available; otherwise it opens the official `https://chatgpt.com/` website.
- **Open browser** opens a web page using the Android default browser.

These are foreground, user-tapped actions. Android background activity-launch restrictions are respected.

## Disconnect
Tap **Unpair locally** to erase the stored Hub credentials while preserving the installation's Device ID. Then revoke the device from the Hub owner device registry. Local unpairing alone does not revoke the server-side device record.

## Android local push-to-talk

On Android 12 (API 31) or newer, tap **شروع فرمان صوتی (محلی)** and grant microphone permission when prompted. The app uses Android's on-device speech recognizer only (`createOnDeviceSpeechRecognizer`) with `fa-IR`; it does not fall back to a network recognizer. Each capture is automatically stopped after 15 seconds. Availability and Persian language support depend on the device's installed offline recognition service. The only voice actions are opening ChatGPT or the browser. Unsupported speech is rejected. Listening is stopped when the activity leaves the foreground. Local Persian text-to-speech is used when the installed system voice supports it; otherwise the text response remains visible.

## Online and offline text assistant

The app includes a separate text assistant:

- **Offline:** no network inference; only the explicit local ChatGPT/browser command allowlist is available. There is no on-device generative LLM in this preview.
- **Online:** enter an API key you control, tap **ذخیره امن کلید آنلاین**, then select Online and send a text prompt. The key is encrypted using Android Keystore AES-GCM and is not included in the APK.
- **Hybrid:** attempts the online request first; if it fails, it can execute only a recognized local allowlisted app-launch command. Other prompts return a clear message that an offline LLM is not installed.

The user must explicitly tap Send while Online or Hybrid is selected. Only the entered text prompt is sent to the official HTTPS API; microphone audio, screenshots, files, and Hub credentials are not included. API usage may be billed separately from a ChatGPT subscription. Remove the saved key with **حذف کلید آنلاین**.

## Current limitations
This is an Android companion preview, not full Android remote-control. Screenshot capture requires a separate Android MediaProjection consent flow and foreground service, so it is not enabled. Remote task dispatch awaits the Hub task-dispatch API release. The app does not perform arbitrary shell commands, hidden monitoring, unrestricted file access, or background UI interaction.
