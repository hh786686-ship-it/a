# Live Chinese Offline — Galaxy A16 5G

An Android (ARM64) **true streaming** microphone transcription app. Free, private, no Google speech SDK and no internet permission. Mandarin, Cantonese, and English supported by one quantized streaming Paraformer ASR model via sherpa-onnx.

## Behaviour
- Partials update during speech using 100 ms input blocks (subject to model context and phone compute speed).
- A pause of about 0.8 sec commits a segment; text is visible before then.
- Start/stop, copy, text export using Android's document picker, and clear.
- Entire int8 ASR model bundled in the APK at build time; **no internet after or during installation is needed for recognition**.
- No call recording, background recording, or capturing audio from another app. Foreground microphone only.
- Model time metric is just processing time per 100 ms chunk and is not a claim of total latency.

## Build
Use GitHub Actions workflow `.github/workflows/build-live-chinese.yml` (push or manual workflow_dispatch). Debug APK is published under workflow run > Artifacts > `LiveChineseOffline-A16-APK`.

The workflow downloads these official assets at build time (with SHA-256 checks for models):
- https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-trilingual-zh-cantonese-en
- https://github.com/k2-fsa/sherpa-onnx

Building requires internet access, but **using the installed app does not**. Source code is not the APK. The application has not been benchmarked on a physical A16 5G. The 1–2 sec latency objective is a target rather than a verified guarantee.
