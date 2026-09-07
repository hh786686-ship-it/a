# Mandarin Call Captions v7 — free offline SenseVoice

This version replaces Android's built-in speech recognizer with **SenseVoice Small INT8** through **sherpa-onnx**.

## What changed
- Free: no API key, no subscription, no per-minute charge.
- Offline after installation: recognition runs locally on the phone.
- Mandarin forced with `language = "zh"`.
- Chinese characters only; no pinyin and no translation.
- Same adjustable overlay width, height, font size, and visible line count.
- Microphone/call mode uses SenseVoice.
- Internal playback/video mode uses Android Playback Capture + SenseVoice.

## Important
The model is about 228 MB, so the APK will be large (roughly 250 MB+). The GitHub workflow downloads the model during the build and packages it into the APK.

SenseVoice is an offline/non-streaming model, so this app simulates live captions by decoding roughly 3-second chunks. That means captions arrive in short bursts rather than character-by-character.

Internal audio still depends on Android Playback Capture. DRM/protected apps and ordinary cellular-call downlink may block capture. For a cellular call, speakerphone + microphone mode remains the practical path on a normal third-party Android app.

## GitHub build
If this project is uploaded as a subfolder such as `MandarinCallCaptions-7`, edit the repository-level workflow so the model-download/build steps use `working-directory: MandarinCallCaptions-7` and prefix the artifact path with `MandarinCallCaptions-7/`.
