---
title: Phase 16 — Offline native stack plan
---

# Phase 16 — Offline native stack plan

Status: **Partial landing (updated 2026-07-14)** — Whisper JNI and Silero VAD
are wired into the current build, while Piper native inference remains a
separate porting project. This page retains the original Phase 16 design and
records the current landing state so it is not mistaken for the release gate.

Current implementation truth:

- Whisper: `externalNativeBuild` is enabled, `libwhisper_jni.so` is packaged
  for the arm64 standard/full builds, and `SttModule` routes to
  `WhisperSttProvider` when the native library and model are present. Real-device
  transcription accuracy, endpoint behavior, and latency are still unverified.
- Silero VAD: model downloader, ONNX session, engine, Settings card, and
  amplitude fallback are shipped. Live-room quality is still unverified.
- Piper: Kotlin provider, voice downloader, fallback behavior, and explicit
  offline-profile truth are shipped; `piper_jni` is not built because this
  checkout has no Android-compatible piper-phonemize/espeak-ng artifacts and
  the checked-in Piper CMake graph is a desktop executable build. Selecting
  Piper currently falls back to Android TTS. This is a native dependency
  approval/porting gate, not a missing Kotlin toggle.
- For the product-level decision and acceptance gates, use
  [smart-speaker-audit.md](smart-speaker-audit.md) and
  [offline-stack-smoke-test.md](offline-stack-smoke-test.md).

### Follow-up checklist (whisper.cpp v1.8.4 landed)

1. ~~Update `.github/workflows/ci.yml` to init submodules before build (`submodules: recursive` on the `actions/checkout@v4` step) — currently only `release.yml` does this.~~ **Done.** Both `ci.yml` and `lint.yml` now check out submodules recursively, so every PR build has the full whisper.cpp / llama.cpp trees available.
2. ~~Wire `add_subdirectory(whisper.cpp)` into `app/src/main/cpp/CMakeLists.txt` behind a feature flag so debug builds without the submodule still link.~~ **Done.** The submodule is added with an `EXISTS`-based guard and `EXCLUDE_FROM_ALL`; `externalNativeBuild` is now enabled in `app/build.gradle.kts` with NDK 28.2.13676358 and the packaged Whisper JNI target is compile-verified. Physical-device transcription remains open.
3. ~~Add `whisper_jni.cpp` binding + a `WhisperJni` Kotlin class mirroring the llama.cpp pattern.~~ **Done.** `app/src/main/cpp/whisper_jni.cpp` defines `nativeLoadModel` / `nativeTranscribe` / `nativeUnloadModel` against the `whisper_full` API; `WhisperCppBridge` is the Kotlin-side wrapper (package `com.opendash.app.voice.stt.whisper`) with lazy `System.loadLibrary("whisper_jni")`, graceful `UnsatisfiedLinkError` fallback, PCM float32/16 kHz input contract, and optional translate flag. CMakeLists produces `libwhisper_jni.so` only when the submodule is initialised (`if(TARGET whisper)` guard). Externally-linked contract: callers pass 16 kHz mono float PCM in [-1.0, 1.0]; translation vs transcription is controlled by the `translate` boolean; language code is ISO 639-1 or "auto". Tests cover graceful missing-library behaviour on JVM host.
4. ~~Replace `OfflineSttStub` routing in `DelegatingSttProvider` with a real `WhisperSttProvider` once the JNI is stable.~~ **Done.** `WhisperSttProvider` emits a single `SttResult.Final` on a batch `whisper_full` run over captured PCM; gates on the native library, model file, and microphone permission. The bridge is compile-verified and the Kotlin error surface is unit-tested; real-device accuracy and performance are still open.
5. ~~Add a whisper model downloader mirroring `ModelDownloader`'s Range-resume support (Phase 14.6).~~ **Done.** `WhisperModelDownloader` mirrors the Range-resume shape (`bytes=N-` on retry, 206-aware append, 200-fallback truncate, temp `.downloading` file kept across failures). `WhisperModelCatalog` hardcodes three curated GGML variants from `ggerganov/whisper.cpp` (tiny / base / small, q5_1 quantisation). Storage at `filesDir/whisper/<filename>` matches the default `modelPathProvider` in `SttModule` — so a successful download immediately satisfies `WhisperSttProvider`'s model-file gate. Tests: 6 MockWebServer cases (fresh download, 206 append, 200 fallback, 404 error keeps temp, isDownloaded state, delete+reset). **Settings UI** shipped via `WhisperModelsCard` (collapses into the STT section when the whisper route is selected).

### Follow-up checklist (piper v1.2.0 landed, P14.9)

Piper's CMake graph is significantly more complex than whisper.cpp —
it depends on `piper-phonemize`, `espeak-ng`, and ONNX Runtime — so the
approach is more conservative than whisper:

1. ~~Add `app/src/main/cpp/piper/` submodule pinned to v1.2.0.~~ **Done.** CI already pulls submodules recursively so no workflow change needed.
2. Obtain explicit approval for the native dependency expansion, then provide
   Android builds of piper-phonemize and espeak-ng plus an Android-compatible
   ONNX Runtime linkage. The current checkout has only desktop Piper archives;
   do not add a fake CMake target or claim `piper_jni` availability.
3. Add `piper_jni.cpp` binding + `PiperCppBridge` Kotlin class paralleling `WhisperCppBridge` — `nativeLoadModel(path, configPath)`, `nativeSynthesize(text, speakerId) → ShortArray`, `nativeUnloadModel(handle)`.
4. Replace `PiperTtsProvider` placeholder in `TtsModule` with a real implementation routed through the bridge.
5. Add a piper voice downloader — piper voices are distributed as paired `.onnx` + `.onnx.json` files on Hugging Face (`rhasspy/piper-voices`). Mirror `WhisperModelDownloader`'s Range-resume shape.

The P14.1 (STT), P16.2 (Silero), and P14.9 (TTS) scaffolding has landed to
different degrees. Whisper and Silero have active paths with fallback gates;
Piper still falls back to Android TTS until its native port is completed.

## Scope

The sections below are the original design proposal. Where they describe
partial-result Whisper capture or a Piper implementation replacing Android
TTS, treat that as historical target behavior; the current implementation
truth above is authoritative.

Three engines:

1. **whisper.cpp** — on-device Whisper STT replacing `OfflineSttStub` on the `WHISPER_OFFLINE` route.
2. **sherpa-onnx silero-vad** — voice activity detection feeding Whisper's endpoint decisions.
3. **piper-cpp** — neural TTS replacing the `PiperTtsProvider` fallback.

All three are pure C++ libraries with GGML / ONNX model files downloaded separately. None require Play Services or network at runtime.

## Why this needs permission

CLAUDE.md forbids adding dependencies without the user's say-so. These three bring:

- git submodules: `third_party/whisper.cpp`, `third_party/piper`, `third_party/sherpa-onnx` (~ a few hundred MB each at clone time, small binaries at build time)
- CMake entries in `app/src/main/cpp/CMakeLists.txt` — we already have one for llama.cpp
- Native build time bump: +60-120 s on clean, cached thereafter
- APK size: ~1-3 MB per engine (stripped arm64-v8a only)
- Model files downloaded at runtime (not shipped in the APK)

All three are permissively licensed (MIT / Apache-2.0). No new runtime permissions.

## Proposed module layout

```
app/src/main/cpp/
├── CMakeLists.txt                        # top-level, adds every sub-lib
├── osspeaker_jni.cpp                     # the Kotlin/JNI binding entry points
├── whisper_bridge/
│   ├── CMakeLists.txt                    # add_subdirectory(whisper.cpp)
│   └── whisper_jni.cpp                   # WhisperSttProvider ↔ whisper_full
├── piper_bridge/
│   ├── CMakeLists.txt                    # add_subdirectory(piper)
│   └── piper_jni.cpp                     # PiperTtsProvider ↔ piper::synthesize
├── silero_bridge/
│   ├── CMakeLists.txt                    # add_subdirectory(sherpa-onnx)
│   └── silero_jni.cpp                    # SileroVadProvider ↔ silero_vad_detector
└── third_party/ (submodules — user-approved only)
```

Each engine gets its own JNI entry class, mirrored on the Kotlin side as:

- `WhisperSttProvider` : `SpeechToText` — replaces `OfflineSttStub` for `WHISPER_OFFLINE`. Holds a single native `whisper_context*` pointer; `startListening()` feeds AudioRecord PCM to `whisper_full()` in 30-s chunks; emits `SttResult.Partial` per `whisper_full_n_segments()` iteration and `Final` on end-of-speech.
- `PiperTtsProvider` (replace existing placeholder body) — no more fallback to Android TTS once the model is downloaded. Uses `piper::TtsRunner::run()` to produce 16-bit PCM, plays via `AudioTrack` or blits to `MediaPlayer`.
- `SileroVadProvider` : new `VadEngine` interface — continuous 10 ms frame eval, emits `VadEvent.SpeechStart` / `VadEvent.SpeechEnd`. Drives `AndroidSttProvider` and the future Whisper path both.

## Model management

Whisper and Piper models are 30-200 MB each. We already have a `ModelDownloader` with HTTP Range resume (P14.6). Extend it with a `NativeModelRegistry` that lists models per engine, letting the user pick (e.g. "Whisper small.en (English-only, fast)" vs "Whisper medium (multilingual, heavy)").

The existing `ModelSetupScreen` gets three tabs or a segmented picker: LLM / STT / TTS. Each tab reuses the resume-capable downloader.

## Original delivery sequence (historical)

- **Phase 16.1**: VAD first. It's the simplest (tiny model, ONNX Runtime already popular, fewer edge cases) and unblocks Whisper integration since Whisper needs endpoint detection.
- **Phase 16.2**: Whisper second. Builds on VAD. `OfflineSttStub` swapped out.
- **Phase 16.3**: Piper third. Independent of the other two; land after Whisper so we can ship the full offline chain in one release note.

Each of the three lands in a dedicated PR (submodule add + CMake + JNI + Kotlin side + tests + docs). The corresponding provider / stub in Kotlin stops erroring and starts running.

## Risks called out

- **APK size**: Three new native libs stripped for arm64-v8a only push us from ~25 MB to ~35 MB. x86_64 emulator support would double that — we'll keep the build arm64-v8a-only (matches our existing llama.cpp config).
- **NDK version drift**: The current build pins `ndkVersion = "28.2.13676358"` in `app/build.gradle.kts`; keep this aligned with CI and the real-device APK used for validation.
- **Symbol collisions**: whisper.cpp and piper both vendor GGML. We need either a shared `ggml_core` submodule or namespace hiding via `target_link_libraries` + `set_target_properties(... PROPERTIES POSITION_INDEPENDENT_CODE ON)` + `-fvisibility=hidden` on each target. Will verify during Phase 16.1 bring-up.
- **First-run UX**: Downloading 3 x 30-200 MB models is a lot. Onboarding should default to one bundle (small Whisper + tiny Piper voice) and let power users add more later.
- **Testability**: The Kotlin sides are JVM-testable by mocking the provider interface (already proven with the current stubs). The JNI layer itself is instrumented-test-only; we'll scope CI to smoke-run an on-device instrumented test per engine.

## Out of scope (for Phase 16)

- Replacing llama.cpp — on-device LLM already works via LiteRT-LM. Whisper/Piper are additive, not substitutive.
- GPU acceleration for the native engines — Whisper's Vulkan backend is experimental on Android; we'll ship CPU-only first.
- Multi-language Piper voice hot-swap mid-utterance — voice changes happen between utterances.
- Custom wake-word training (openWakeWord) — separate track; Vosk keyword-spot stays for now.

## Remaining decision boundary

Whisper and Silero dependencies have already landed. The remaining user-visible
decision is whether to fund the separate Piper Android port (piper-phonemize,
espeak-ng, ONNX Runtime packaging, JNI, ABI/build-size work, and physical voice
quality validation). Until that decision and implementation are complete, the
supported TTS fallback is Android TTS and the product must not claim Piper-native
offline speech.
