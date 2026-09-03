# Mandarin Call Captions — microphone + internal phone audio

Android accessibility captioning starter for **Mandarin Chinese speech → Chinese characters (汉字)**. No pinyin and no translation.

## Caption sources

### 1. Calls / microphone
Enable the accessibility service and microphone permission. For an ordinary cellular call, turn on speakerphone so the microphone can hear the other caller.

### 2. Videos / phone playback audio
On Android 13+ tap **Start phone audio captions**. Android shows its standard media-projection permission dialog. After approval, compatible media/game/app playback audio is captured internally and fed to the on-device Mandarin recognizer.

Android playback capture has platform restrictions:
- the playing app must allow playback capture;
- protected/DRM content can block it;
- the playing and capturing apps must be in the same Android user profile;
- direct cellular-call downlink is still not available to ordinary third-party apps.

The internal-audio path uses `AudioPlaybackCaptureConfiguration` + `AudioRecord`, then supplies PCM to Android speech recognition using `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 13/API 33+). Recognition-service support for injected audio can vary by device/vendor.

## Floating window controls
- Width: 50–100% of screen
- Height: 10–70% of screen
- Text size: 18–38sp
- Visible lines: 1–6

New captions appear at the bottom; when the line limit is reached the oldest line is removed.

## Build
Use the included GitHub Actions workflow (`.github/workflows/build-apk.yml`) or open in Android Studio. The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`
