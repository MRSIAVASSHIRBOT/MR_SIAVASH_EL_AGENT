# Android companion quick start

## Requirements
- Android 8.0 (API 26) or newer.
- A Hub deployment reachable from the phone over HTTPS.
- Android Studio with JDK 17, or Gradle 8.9 with Android SDK 35.
- A Hub owner account. Do not put the owner's bearer token in the Android app.

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
    "advertised_capabilities": ["device.heartbeat", "app.launch"],
    "scopes": ["device.heartbeat", "app.launch"]
  }'
```

4. Copy the returned one-time `pairing_code` into the Android app, enter the same HTTPS Hub origin, and tap **Pair with Hub**.
5. The app exchanges the code directly with Hub. The code is not stored. Device credentials are encrypted using an AES-GCM key held by Android Keystore.
6. Tap **Send heartbeat now** to verify connectivity. WorkManager schedules a network-constrained periodic heartbeat; Android controls the exact execution time to preserve battery.

The owner chooses the scopes in the pairing challenge. Device-advertised capabilities do not grant permissions. For this preview, request only `device.heartbeat` and `app.launch`. Do not grant `screen.capture`: Android does not implement remote screenshot capture. The app rejects any scope outside this exact set.

## Local actions
- **Open ChatGPT** opens the installed ChatGPT Android app when available; otherwise it opens the official `https://chatgpt.com/` website.
- **Open browser** opens a web page using the Android default browser.

These are foreground, user-tapped actions. Android background activity-launch restrictions are respected.

## User-selected local text files

Tap **انتخاب و پیش‌نمایش فایل متنی** and choose a document in Android's system picker. The app uses the Storage Access Framework; it does not request broad storage access. Only text and the supported structured-text MIME types are previewed. Reads are limited to 64 KiB and displayed output to 12,000 characters. Invalid UTF-8, binary content, PDF, and unsupported file types are rejected. Read access is persisted only when the document provider explicitly returns both read and persistable grants; otherwise the temporary picker grant is used for the current session. The contents remain on-device and are not included in cloud chat or Hub requests.

## Online structured planner and reviewed execution (Android 0.5.0)

The planner uses a zero-cost-first provider chain. It does not use the legacy OpenAI API key and has no paid-provider fallback.

1. In the Planner panel, enter a Google Gemini API key and/or an OpenRouter API key using the matching **ذخیره امن کلید** control.
2. The default order is Google Gemini API Free tier (`gemini-4-hydrogen`), followed by OpenRouter's free-model router (`openrouter/free`). Only providers with a locally saved key are attempted.
3. Keep the Google AI Studio project on the Free tier with billing disabled. The app cannot inspect the project's billing configuration, so it cannot guarantee zero cost if billing is enabled. OpenRouter is restricted to a free model route; quotas and availability may change.
4. When a provider fails, the same typed request may be sent to the next configured provider. Free-tier providers may process prompts under their own data-use terms; do not submit sensitive or confidential information.

When **ساخت برنامهٔ اجرایی (بدون اجرا)** is tapped, only the text typed into the planner request field and the fixed system prompt are sent to the configured free provider. Selected files, microphone audio, screenshots, Hub credentials, and local memory are excluded. The app validates the returned JSON locally and displays a plan; no step runs automatically.

Each proposed step must pass the local capability policy and requires a separate owner confirmation before execution. Supported operations are limited to:
- `open_app`: only the existing `chatgpt` and `browser` aliases
- `open_url`: HTTPS only, without embedded credentials
- `read_selected_document`: no path is accepted; a user must choose a document through Android's picker
- `show_text`: bounded plain text

Unknown fields, unsupported operations, invalid URLs, duplicate IDs, forward dependencies, excessive output, and malformed plans are rejected. App launches and HTTPS navigation are dispatched through Android intents and remain pending until the owner returns and explicitly confirms the result. Document steps use the system picker and verify the returned read grant. A failed step stops the rest of the plan. Execution state is held only in memory; closing or restarting the app discards it rather than resuming device actions.

The legacy OpenAI key may remain in encrypted local storage for compatibility, but this planner does not call the OpenAI API. No paid API request or automatic provider upgrade is performed by this planner.


## Owner-approved Hub app-launch tasks

The Tools screen includes **Check Hub tasks** for a paired device with the owner-granted `app.launch` scope. Claiming is manual and only available while the app is in the foreground. Each supported ChatGPT/browser launch is shown in a separate approve/reject dialog; no app opens before approval. Android does not accept remote screenshots or background-launch tasks. The Hub lease and the task's execution deadline are checked before an outcome is reported. If the action exceeds its deadline or lease, the result is left unknown and is never replayed. The timeout is an outcome-reporting deadline, not a mechanism that can forcibly stop an Android intent. This feature remains in draft PR #25 and requires a deployed Hub plus physical-device acceptance.

## Disconnect
Tap **Unpair locally** to erase the stored Hub credentials while preserving the installation's Device ID. Then revoke the device from the Hub owner device registry. Local unpairing alone does not revoke the server-side device record.

## Android local push-to-talk

On Android 12 (API 31) or newer, tap **شروع فرمان صوتی (محلی)** and grant microphone permission when prompted. The app uses Android's on-device speech recognizer only (`createOnDeviceSpeechRecognizer`) with `fa-IR`; it does not fall back to a network recognizer. Each capture is automatically stopped after 15 seconds. Availability and Persian language support depend on the device's installed offline recognition service. Voice actions are limited to opening ChatGPT, opening the browser, or requesting a call to the owner-selected contact. A recognized call phrase only opens a confirmation dialog; the call starts only after the owner taps **تماس** and grants Android's `CALL_PHONE` permission. Unsupported speech is rejected. Listening is stopped when the activity leaves the foreground. Local Persian text-to-speech is used when the installed system voice supports it; otherwise the text response remains visible.

## Current limitations
This is an Android companion preview, not full Android remote-control. Screenshot capture requires a separate Android MediaProjection consent flow and foreground service, so it is not enabled. The manual foreground-only Hub app.launch consumer is implemented in draft PR #25 but is not merged or live-device accepted. The app does not perform arbitrary shell commands, hidden monitoring, unrestricted file access, or background UI interaction.
