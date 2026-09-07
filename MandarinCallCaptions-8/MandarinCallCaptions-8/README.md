# Mandarin Call Captions v8 — Complete Samsung A16 Build

This is the complete corrected v8 source package.

## Included fixes

- `compileSdk = 36`
- `targetSdk = 35`
- `minSdk = 31`
- Samsung Galaxy A16 ARM64-only APK
- legacy native library packaging for Samsung compatibility
- `android:extractNativeLibs="true"`
- SenseVoice Small INT8 offline Mandarin speech recognition
- Chinese characters only
- no pinyin
- no translation
- adjustable caption window width
- adjustable caption window height
- adjustable text size
- adjustable caption line count
- microphone caption mode
- compatible internal phone/video-audio capture mode
- GitHub downloads the official SenseVoice model during build
- GitHub verifies the final APK signature before upload

## IMPORTANT: Upload layout

The ZIP contains:

- `.github/workflows/build-apk.yml`
- `MandarinCallCaptions-8/`

Upload BOTH of those at the ROOT of your GitHub repository.

After upload, your GitHub repository should look like:

```
.github/
  workflows/
    build-apk.yml

MandarinCallCaptions-8/
  app/
  build.gradle.kts
  settings.gradle.kts
  ...
```

Do NOT put `.github` inside `MandarinCallCaptions-8`.

## Build

1. Open GitHub.
2. Go to **Actions**.
3. Select **Build Android APK v8**.
4. Press **Run workflow**.
5. Wait for a green check.
6. Open the run's **Summary**.
7. Under **Artifacts**, download `MandarinCallCaptions-v8-SamsungA16`.
8. Unzip the artifact.
9. Install `app-debug.apk` on the Samsung A16.

The GitHub build downloads the large SenseVoice model automatically, so the source ZIP itself stays small.
