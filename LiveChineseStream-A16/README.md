# Live Chinese Offline — Galaxy A16 5G

An Android (ARM64) **true streaming** microphone transcription app. Free, private, no Google speech SDK and no internet permission. Mandarin and English via an offline streaming Paraformer for immediate partial results, and a smaller offline CT-Transformer adding Chinese punctuation (，、。) and appropriate ?/! marks. The SenseVoice second-pass model has been removed. The high-accuracy pass runs on a background executor and may take longer than 2 seconds to replace the provisional words.

## Behaviour
- Partials update during speech using 100 ms input blocks (subject to model context and phone compute speed).
- A pause of about 0.8 sec commits a provisional segment with a full stop. A second-pass recognizer and punctuation model can then improve the segment without blocking the live recognizer.
- Start/stop, copy, text export using Android's document picker, and clear.
- All streaming, offline-correction, and punctuation ONNX models are bundled in the APK at build time; **no internet is needed to transcribe**.
- No call recording, background recording, or capturing audio from another app. Foreground microphone only.
- Model time metric is just processing time per 100 ms chunk and is not a claim of total latency.

## Build
Use GitHub Actions workflow `.github/workflows/build-live-chinese.yml` (push or manual workflow_dispatch). Debug APK is published under workflow run > Artifacts > `LiveChineseOffline-A16-APK`.

The workflow downloads these official assets at build time (with SHA-256 checks for models):
- https://huggingface.co/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en
- https://github.com/k2-fsa/sherpa-onnx

Building requires internet access, but **using the installed app does not**. Source code is not the APK. The application has not been benchmarked on a physical A16 5G. The 1–2 sec latency objective is a target rather than a verified guarantee.

## Accuracy trade-off
This version embeds a larger offline second-pass and punctuation model, so the APK and memory use are higher than v1.2. Streaming interim words are shown first; corrections can arrive later. Accuracy improvements are model-dependent and not verified against the user's speech; 1–2-second correction latency is not guaranteed.

## Lightweight v1.4
SenseVoice has been removed entirely. Mandarin/English streaming Paraformer is unchanged. A punctuation-only neural model processes each finished segment on a worker thread, with an immediate fallback Chinese full stop. This reduces APK size but does not improve word recognition accuracy. Punctuation model output is not guaranteed to be linguistically perfect.

## v1.5 Qwen3-ASR correction (experimental)
Bundles the 0.6B INT8 Qwen3-ASR ONNX model (conv frontend, encoder, decoder, tokenizer). It runs after Paraformer commits a short speech segment, using queued background inference; speech still appears immediately from Paraformer. The first pass, punctuation-only fallback, and Mandarin/English restriction stay enabled. The APK is substantially larger and may exhaust RAM on lower-end phones. Qwen correction speed and accuracy on Galaxy A16 5G are not independently measured. Built-in CI checks validate model loading; an emulator is not a replacement for physical device timing.

## v1.6: Qwen3-only mode
Removed all other ASR and neural punctuation models from this build. It uses Qwen3-ASR 0.6B INT8 for offline Mandarin/English speech recognition, with 2-second chunks and lightweight rule-based punctuation. Text can only appear after each chunk and its processing delay. It is not guaranteed to respond in 2 seconds, nor to avoid memory crashes on a physical A16. CI tests verify native Qwen decoding on 2 seconds of silence and Android microphone startup.
