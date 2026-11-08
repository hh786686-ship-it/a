# Attempted full MinerU 4 Standard installation on Android ARM64

Date: 2026-11-08
GitHub Actions: https://github.com/hh786686-ship-it/a/actions/runs/42499791667

## Attempt

A separate minimal Android application was configured with:
- Python 3.12 via Chaquopy 17.0.0
- `arm64-v8a` Android target
- unmodified Python dependency requirement `mineru>=4.0,<5`
- Android SDK 35 and Gradle 8.11.1

Command: `gradle --no-daemon :app:assembleDebug --stacktrace`

## Actual result: FAILED BEFORE APK

Gradle task: `:app:installDebugPythonRequirements FAILED`.

Chaquopy's Android pip resolver searched multiple MinerU releases (4.0.0 through
4.1.7). Its final error:

```text
ERROR: Cannot install mineru==4.0.0 ... mineru==4.1.7 because these
package versions have conflicting dependencies.
Additionally, some packages in these conflicts have no matching
distributions available for your environment:
    docvortex
    mineru-llama-cpp
ERROR: ResolutionImpossible
```

Therefore no full MinerU Standard APK was produced. The GitHub workflow is
expected to fail at the verification stage; it is not a success.

This failure demonstrates **missing compatible Android native dependency
builds**, not proof of a model crash on an A16. Full runtime inference never
started; it would be misleading to claim we observed a crash or measured
Android inference speed.

Further work would require porting/building at least the missing libraries for
Android ARM64, integrating compatible VLM inference and complete layout/ZIP
output pipeline, then testing actual device memory and performance.
The existing ~53MB MinerU-ONNX experimental APK includes only small OCR
detection/recognition models, NOT the full Standard pipeline.
