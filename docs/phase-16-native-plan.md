---
title: Phase 16 — Offline native stack plan
---

# Phase 16 — Offline native stack plan

Status: **Partial landing** — whisper.cpp submodule landed (pinned to v1.8.4 under `app/src/main/cpp/whisper.cpp/`). No CMake wire-up yet — the submodule sits dormant so CI builds stay green while the JNI bridge + `WhisperSttProvider` impl are designed. This page captures the approach for the three native-JNI items in Phase 16 so when each submodule + CMake entry lands, the wiring decisions are already made.

### Follow-up checklist (whisper.cpp v1.8.4 landed)

1. ~~Update `.github/workflows/ci.yml` to init submodules before build (`submodules: recursive` on the `actions/checkout@v4` step) — currently only `release.yml` does this.~~ **Done.** Both `ci.yml` and `lint.yml` now check out submodules recursively, so every PR build has the full whisper.cpp / llama.cpp trees available.
2. ~~Wire `add_subdirectory(whisper.cpp)` into `app/src/main/cpp/CMakeLists.txt` behind a feature flag so debug builds without the submodule still link.~~ **Done.** The submodule is added with an `EXISTS`-based guard and `EXCLUDE_FROM_ALL` so the `whisper` static library target is only built when an explicit `target_link_libraries(... whisper ...)` pulls it in. Shared GGML backend flags (GGML_OPENMP / GGML_METAL / GGML_CUDA / GGML_VULKAN) are set at top level so llama.cpp and whisper.cpp stay in sync. External-native-build is still commented out in `app/build.gradle.kts`, so the CMake graph is defined but dormant until someone re-enables native builds.
3. ~~Add `whisper_jni.cpp` binding + a `WhisperJni` Kotlin class mirroring the llama.cpp pattern.~~ **Done.** `app/src/main/cpp/whisper_jni.cpp` defines `nativeLoadModel` / `nativeTranscribe` / `nativeUnloadModel` against the `whisper_full` API; `WhisperCppBridge` is the Kotlin-side wrapper (package `com.opendash.app.voice.stt.whisper`) with lazy `System.loadLibrary("whisper_jni")`, graceful `UnsatisfiedLinkError` fallback, PCM float32/16 kHz input contract, and optional translate flag. CMakeLists produces `libwhisper_jni.so` only when the submodule is initialised (`if(TARGET whisper)` guard). Externally-linked contract: callers pass 16 kHz mono float PCM in [-1.0, 1.0]; translation vs transcription is controlled by the `translate` boolean; language code is ISO 639-1 or "auto". Tests cover graceful missing-library behaviour on JVM host.
4. ~~Replace `OfflineSttStub` routing in `DelegatingSttProvider` with a real `WhisperSttProvider` once the JNI is stable.~~ **Done.** `WhisperSttProvider` emits a single `SttResult.Final` on a batch `whisper_full` run over captured PCM; gates on `WhisperCppBridge.isAvailable()` (native lib) + model file presence + microphone permission. `AudioRecordPcmSource` captures 16 kHz mono float32 PCM via `AudioRecord(VOICE_RECOGNITION)`. `SttModule` now builds a real `WhisperSttProvider` when the bridge is available and falls back to `OfflineSttStub("Whisper")` otherwise — so on stock builds (externalNativeBuild commented out) user-visible behaviour is unchanged. Tests: 9 JVM cases covering the full error surface (no lib, no model, load failed, empty capture, SecurityException from pcm, bridge exception, stop path).
5. ~~Add a whisper model downloader mirroring `ModelDownloader`'s Range-resume support (Phase 14.6).~~ **Done.** `WhisperModelDownloader` mirrors the Range-resume shape (`bytes=N-` on retry, 206-aware append, 200-fallback truncate, temp `.downloading` file kept across failures). `WhisperModelCatalog` hardcodes three curated GGML variants from `ggerganov/whisper.cpp` (tiny / base / small, q5_1 quantisation). Storage at `filesDir/whisper/<filename>` matches the default `modelPathProvider` in `SttModule` — so a successful download immediately satisfies `WhisperSttProvider`'s model-file gate. Tests: 6 MockWebServer cases (fresh download, 206 append, 200 fallback, 404 error keeps temp, isDownloaded state, delete+reset). **Settings UI for selecting / downloading models is a follow-up** — the downloader is wired but not surfaced yet.

The P14.1 (STT) and P14.9 (TTS) scaffolding already ships: `DelegatingSttProvider` + `OfflineSttStub` + `PiperTtsProvider`. They route on the same provider selector as the shipped backends; today they emit "coming soon" or fall back. This plan describes replacing the stubs with real engines.

## Scope

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

## Delivery sequence

- **Phase 16.1**: VAD first. It's the simplest (tiny model, ONNX Runtime already popular, fewer edge cases) and unblocks Whisper integration since Whisper needs endpoint detection.
- **Phase 16.2**: Whisper second. Builds on VAD. `OfflineSttStub` swapped out.
- **Phase 16.3**: Piper third. Independent of the other two; land after Whisper so we can ship the full offline chain in one release note.

Each of the three lands in a dedicated PR (submodule add + CMake + JNI + Kotlin side + tests + docs). The corresponding provider / stub in Kotlin stops erroring and starts running.

## Risks called out

- **APK size**: Three new native libs stripped for arm64-v8a only push us from ~25 MB to ~35 MB. x86_64 emulator support would double that — we'll keep the build arm64-v8a-only (matches our existing llama.cpp config).
- **NDK version drift**: Pin `ndkVersion = "26.1.10909125"` (current) in `app/build.gradle.kts`; document in [conventions.md](conventions.md).
- **Symbol collisions**: whisper.cpp and piper both vendor GGML. We need either a shared `ggml_core` submodule or namespace hiding via `target_link_libraries` + `set_target_properties(... PROPERTIES POSITION_INDEPENDENT_CODE ON)` + `-fvisibility=hidden` on each target. Will verify during Phase 16.1 bring-up.
- **First-run UX**: Downloading 3 x 30-200 MB models is a lot. Onboarding should default to one bundle (small Whisper + tiny Piper voice) and let power users add more later.
- **Testability**: The Kotlin sides are JVM-testable by mocking the provider interface (already proven with the current stubs). The JNI layer itself is instrumented-test-only; we'll scope CI to smoke-run an on-device instrumented test per engine.

## Out of scope (for Phase 16)

- Replacing llama.cpp — on-device LLM already works via LiteRT-LM. Whisper/Piper are additive, not substitutive.
- GPU acceleration for the native engines — Whisper's Vulkan backend is experimental on Android; we'll ship CPU-only first.
- Multi-language Piper voice hot-swap mid-utterance — voice changes happen between utterances.
- Custom wake-word training (openWakeWord) — separate track; Vosk keyword-spot stays for now.

## What we need from the user

One "go ahead" for each of:

- [ ] Add `third_party/whisper.cpp` submodule + CMake entry (P16.1/2 prereq)
- [ ] Add `third_party/piper` submodule + CMake entry (P16.3 prereq)
- [ ] Add `third_party/sherpa-onnx` submodule + CMake entry (P16.1 prereq)

Upper-bound impact: APK +8-10 MB for arm64, clean build +~90 s, no new runtime perms.

Once approved, each follow-up PR implements one engine per ADR decision above.
