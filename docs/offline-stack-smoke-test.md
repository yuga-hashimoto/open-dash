---
title: Offline stack smoke test
---

# Offline stack smoke test

Step-by-step checklist for validating the currently supported offline voice
profile after the native/model gates are ready.
Mirrors [real-device-smoke-test.md](real-device-smoke-test.md) but
focuses on Whisper STT, Silero VAD, and the Piper readiness boundary.

**Current status (2026-07-14)**: `externalNativeBuild` is enabled and the
Whisper JNI library is packaged for the arm64 standard/full builds. Whisper
still needs real-device accuracy and latency validation. Silero VAD has a
downloadable ONNX model and auto-upgrades the Whisper capture path when ready.
Piper voice download/UI/Kotlin fallback exist, but `libpiper_jni.so` is not
packaged; Piper therefore falls back to Android TTS. A fully offline spoken
round-trip is only valid when the selected Android TTS voice is confirmed
installed locally, or after the separate Piper native port lands.

Whenever the diagnostic says **Not ready**, check
[Settings → Advanced → Offline stack status](#step-1--check-the-diagnostic-card)
for per-gate reasons — the ✗ lines tell you which gate is failing.

---

## Before you start

Required:
- Android tablet running OpenDash with native builds enabled
- ~100 MB free storage for a tiny Whisper model and optional Silero model
- Active wifi for the initial model download
- Grant Microphone permission if not already

Optional but recommended:
- External Bluetooth speaker for TTS output checks (the built-in
  AudioTrack path is reused for both Piper and Android TTS, but a
  speaker makes sample-rate conversion artefacts easier to hear)

---

## Step 1 — Check the diagnostic card

1. Open **Settings → Advanced → Offline stack status**.
2. Expect both sections to read:
   ```
   Speech-to-text (Whisper)   Not ready
   ✓ Native library loaded
   ✗ Active model downloaded
   ```
   If the native library row shows ✗, stop and inspect the APK/ABI and build
   variant. Do not infer that Piper is available from Whisper being ready.
3. Downloading the Silero model is optional. When present, the diagnostic
   should say it is ready and the Whisper capture path should select Silero;
   otherwise it must explicitly say that amplitude VAD is the fallback.

---

## Step 2 — Download a Whisper model

1. **Settings → Speech recognition → Whisper (offline)**
2. The Whisper models card unfolds. Tap **Download** on `Whisper tiny
   (multilingual, ~31 MB)`.
3. Progress bar fills in ~10–30 s depending on network. On completion
   the row flips to show ✓ installed + Delete button.
4. Tap the radio on the `tiny` row — it becomes the active model.
5. Diagnostic card's Whisper section should now read:
   ```
   Speech-to-text (Whisper)   Ready
   ✓ Native library loaded
   ✓ Active model downloaded
   ```

## Step 3 — Whisper end-to-end speak test

1. With the app in the foreground, tap the microphone FAB (or wake
   word if enabled).
2. Say: **"What's the time?"** — short, quiet background.
3. Expected: transcript renders in <1 s after you finish speaking
   (amplitude VAD endpoint detection), tool routes to `get_datetime`,
   assistant replies via the configured TTS.
4. Try again with the **Transcription language** picker set to
   **日本語**, say 「今何時？」 — expect Japanese transcription.
5. Flip **Translate to English** on, say 「今何時？」 again — expect
   English transcription of the same utterance.

## Step 4 — Check the Piper boundary

1. **Settings → Text-to-speech provider → Piper (offline)**
2. The Piper voices card unfolds. Tap **Download** on
   `English (US) — Amy (medium)`.
3. Both `.onnx` (~60 MB) and `.onnx.json` (~5 KB) land. The row flips
   to ✓ installed + Preview + Delete.
4. Tap **Preview**. On the current build, expect Android TTS fallback unless
   the APK contains a future `piper_jni` implementation. The preview must still
   produce audible speech and the UI/log must make the fallback explicit.
5. Diagnostic card's Piper section should now read:
   ```
   Text-to-speech (Piper)   Fallback
   ✗ Native library loaded
   ✓ Active voice downloaded
   ```
   A future native Piper build may change this to Ready only after a physical
   voice-quality run.

## Step 5 — Fully-offline round-trip

1. Disable wifi / cellular so the tablet is truly offline.
2. Confirm the local LLM is active (Settings → Providers → Embedded
   local model selected, model downloaded).
3. Utter: **"Set a timer for 30 seconds"**.
4. Expected end-to-end latency budget (record actual values; these are targets):
   - Wake word → listening: ≤500 ms
   - Whisper transcribes: ≤1 s after speech ends
   - LLM tool-call dispatch: ≤500 ms (local Gemma)
   - Selected local TTS synthesises + speaks confirmation: ≤1 s after LLM
5. Total goal: user finishes speaking → confirmation audio starts
   in under 3 seconds with no network access.

## Step 6 — Thermal / battery saver overlap

1. Enable **Settings → Battery saver**.
2. Unplug the tablet and let the battery drift below 20%.
3. The Ambient home screen should show the **Battery saver active —
   wake word paused** chip (P14.8, #472/#474). Diagnostic card still
   shows the actual Whisper/Silero/Piper gate states — they are loaded or
   fallback-only as configured, just gated off by VoiceService until the device charges.
4. Plug back in — chip disappears, wake-word resumes, Whisper +
   Piper still serve the next utterance.

---

## Known gaps

- **No streaming transcription** — `whisper_full` runs as a batch
  after `AudioRecord` capture ends. User feels the `minSpeechMs` +
  `silenceTrailMs` envelope before seeing text. True streaming
  (Whisper partials) is future work.
- **No Japanese Piper voice preview until the voice downloads** —
  `ja_JP-takumi-medium` is only tested after a deliberate
  download + preview.
- **Silero VAD is available but not acoustically validated** — it can still
  fall back to amplitude VAD if its model is absent or fails to load.
- **Piper native TTS is not shipped** — the current provider is a safe fallback
  wrapper. Treat “Piper selected” as a configuration choice, not proof that
  Piper inference is running.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Diagnostic shows ✗ on "Native library loaded" | Inspect the selected APK flavor/ABI, NDK 28.2.13676358 build, and packaged `libwhisper_jni.so`; the current project configuration has `externalNativeBuild` enabled. |
| Whisper transcribes English as Japanese or vice versa | Use the language picker to force the correct language instead of "Auto-detect". |
| Piper Preview falls back to Android TTS | This is expected on the current build because `piper_jni` is not packaged. A downloaded voice alone does not activate native inference. |
| Whisper capture hangs for 15 s regardless of speech | Amplitude VAD threshold may be too low for your room noise. Raise `AmplitudeVad.rmsThreshold` from the default `0.01f` — room tone > 0.01 will prevent endpoint detection. |
| Piper sample plays at wrong pitch / speed | Sample-rate mismatch. `PiperCppBridge.sampleRate()` should return 22050 for medium voices; if it returns 0, the voice isn't loaded. |

## Related

- [phase-16-native-plan.md](phase-16-native-plan.md) — historical design + current native-engine status
- [real-device-smoke-test.md](real-device-smoke-test.md) — the broader wake→STT→LLM→TTS smoke checklist
- [latency-budgets.md](latency-budgets.md) — per-span budgets the amplitude VAD aims to hit
