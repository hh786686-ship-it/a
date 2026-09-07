# Mandarin Call Captions v8 — Samsung A16 compatibility build

This build keeps the SenseVoice offline Mandarin recognition approach but changes
the APK packaging specifically to improve compatibility with modern Samsung phones.

## v8 install changes

- ARM64 (`arm64-v8a`) only
- `compileSdk` / `targetSdk` 35
- `minSdk` 31
- legacy JNI/native-library packaging (`useLegacyPackaging = true`)
- `android:extractNativeLibs="true"`
- GitHub verifies the final APK signature before uploading it
- the official SenseVoice INT8 model is downloaded during the GitHub build

The model is not stored in this source ZIP. The workflow downloads:
- `model.int8.onnx` (~228 MB)
- `tokens.txt`

After installation the speech model is bundled in the APK, so recognition can run offline.

## GitHub

Upload this folder to your repository as:

`MandarinCallCaptions-8`

Then copy the contents of `BUILD_WORKFLOW.yml` into:

`.github/workflows/build-apk.yml`

Commit, open Actions, and run **Build Android APK v8**.

The downloadable artifact will be called:

`MandarinCallCaptions-v8-SamsungA16`

Inside it is `app-debug.apk`.

## If Samsung still says "problem parsing the package"

Do not keep retrying. At that point use ADB installation once (from a Mac/PC), because
ADB returns the exact Android package-manager error code (for example a signature,
SDK, ABI, or manifest failure) instead of Samsung's generic parsing message.
