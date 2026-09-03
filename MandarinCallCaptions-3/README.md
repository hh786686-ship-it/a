# Mandarin Call Captions — Android MVP

An accessibility-focused Android starter app that displays live **Simplified Chinese (Mandarin, `zh-CN`)** speech recognition as a caption overlay.

## What it can do

- Uses Android's on-device `SpeechRecognizer` when the device provides one.
- Requests partial speech-recognition results for lower-latency captions.
- Shows captions in an accessibility overlay above other UI.
- Restarts finite recognition sessions to approximate continuous captioning.
- Does not upload transcripts or save call text.

## Important Android limitation

A normal third-party Android app cannot directly capture the remote side of an ordinary cellular call. Android reserves direct voice-call uplink/downlink capture for privileged/preinstalled apps with `CAPTURE_AUDIO_OUTPUT`.

This MVP therefore uses a legitimate accessibility-service path to keep microphone capture available while a voice call is active. **For ordinary cellular calls, the user should turn on speakerphone** so the device microphone can hear the remote caller.

For production-quality two-sided audio without speakerphone, use one of these product architectures:

1. **Own VoIP calling stack** — route calls through your app (WebRTC/SIP) and transcribe the received PCM stream directly.
2. **OEM/carrier partnership** — ship as a privileged/system component where direct telephony audio capture can be authorized.
3. **System Live Caption integration** — where available from the device vendor, rely on the OS-level accessibility feature rather than attempting forbidden capture.

## Build

Recommended:
- Android Studio Quail 2 (2026.1.2) or newer
- JDK 17
- Android SDK 37
- Android Gradle Plugin 9.3.0

Open this folder in Android Studio, sync Gradle, and run on a real Android 12+ device.

## Setup on device

1. Launch **Mandarin Call Captions**.
2. Tap **Allow microphone**.
3. Tap **Enable caption accessibility service**.
4. In Android Accessibility settings, enable **Mandarin Call Captions**.
5. Start a phone call.
6. Put the call on **speakerphone**.
7. Captions appear near the bottom of the screen.

## Privacy and legal notes

- The sample intentionally uses on-device recognition only and does not store transcripts.
- Speech-recognition availability and Mandarin quality vary by Android device and installed speech service.
- Call-caption/recording consent laws vary by jurisdiction. A production app should give users clear notice and should not silently record or retain call audio/transcripts.
- AccessibilityService usage is subject to Google Play policy. The accessibility purpose should be prominent, truthful, narrowly scoped, and explained in the store listing/onboarding.

## Next production steps

- Add Traditional Chinese (`zh-TW`) and Cantonese (`yue-Hant-HK`) modes.
- Add larger-font/high-contrast caption controls.
- Add speaker labels when using a VoIP audio pipeline.
- Add punctuation stabilization and rolling transcript history.
- Add an explicit privacy screen and consent UX.
- Test across Pixel, Samsung, Xiaomi/Oppo/Vivo devices and multiple Mandarin accents.

## Build an installable APK without Android Studio

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

1. Create a new GitHub repository.
2. Upload all files from this project, including the hidden `.github` folder.
3. Open the repository's **Actions** tab.
4. Select **Build Android APK**.
5. Click **Run workflow**.
6. When the run finishes, open it and download the artifact named **MandarinCallCaptions-debug-apk**.
7. Unzip the artifact. Inside is `app-debug.apk`.
8. Move that APK to your Android phone and open it to install. Android may ask you to allow installs from that browser/files app.

The debug APK is signed automatically with Android's debug signing key and is suitable for testing on your own phone. It is not intended for Google Play distribution.
