---
title: Offline stack smoke test
---

# Offline stack smoke test

Step-by-step checklist for validating the fully-offline voice pipeline
once `externalNativeBuild` is re-enabled and models are downloaded.
Mirrors [real-device-smoke-test.md](real-device-smoke-test.md) but
focuses on the Whisper STT and Piper TTS paths specifically.

**Status on stock builds**: the full Kotlin scaffolding ships today,
but `libwhisper_jni.so` + `libpiper_jni.so` are not included — the
CMake build is commented out in `app/build.gradle.kts:28`. The steps
below assume native builds have been re-enabled (a separate PR that's
gated on the Piper CMake wire-up + NDK approval).

Whenever the diagnostic says **Not ready**, check
[Settings → Advanced → Offline stack status](#step-1--check-the-diagnostic-card)
for per-gate reasons — the ✗ lines tell you which gate is failing.

---

## Before you start

Required:
- Android tablet running OpenDash with native builds enabled
- ~250 MB free storage (tiny + amy voices)
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
   If the native library row shows ✗, native builds aren't on — stop
   here and fix that first (re-enable `externalNativeBuild` + rebuild
   APK).

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

## Step 4 — Download a Piper voice

1. **Settings → Text-to-speech provider → Piper (offline)**
2. The Piper voices card unfolds. Tap **Download** on
   `English (US) — Amy (medium)`.
3. Both `.onnx` (~60 MB) and `.onnx.json` (~5 KB) land. The row flips
   to ✓ installed + Preview + Delete.
4. Tap **Preview**. Expect to hear Amy read the sample sentence in
   US English. On the first preview Piper loads the voice into native
   memory — subsequent previews reuse it.
5. Diagnostic card's Piper section should now read:
   ```
   Text-to-speech (Piper)   Ready
   ✓ Native library loaded
   ✓ Active voice downloaded
   ```

## Step 5 — Fully-offline round-trip

1. Disable wifi / cellular so the tablet is truly offline.
2. Confirm the local LLM is active (Settings → Providers → Embedded
   local model selected, model downloaded).
3. Utter: **"Set a timer for 30 seconds"**.
4. Expected end-to-end latency budget:
   - Wake word → listening: ≤500 ms
   - Whisper transcribes: ≤1 s after speech ends
   - LLM tool-call dispatch: ≤500 ms (local Gemma)
   - Piper synthesises + speaks confirmation: ≤1 s after LLM
5. Total goal: user finishes speaking → confirmation audio starts
   in under 3 seconds with no network access.

## Step 6 — Thermal / battery saver overlap

1. Enable **Settings → Battery saver**.
2. Unplug the tablet and let the battery drift below 20%.
3. The Ambient home screen should show the **Battery saver active —
   wake word paused** chip (P14.8, #472/#474). Diagnostic card still
   shows Whisper / Piper as Ready — they're loaded, just gated off by
   VoiceService until the device charges.
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
- **Silero VAD pending** — amplitude VAD works for clean rooms but
  false-positives on continuous hum (HVAC, fan). Silero ONNX lands
  in Phase 16 once the ONNX Runtime dep is approved.
- **Stock builds stay on Android system STT + Android TTS** — the
  whole stack above only lights up when `externalNativeBuild` is
  re-enabled. Until then the diagnostic card correctly reports "Not
  ready" with the library row showing ✗.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Diagnostic shows ✗ on "Native library loaded" | `externalNativeBuild` is still commented out in `app/build.gradle.kts`. Re-enable, install NDK 27.0.12077973, rebuild. |
| Whisper transcribes English as Japanese or vice versa | Use the language picker to force the correct language instead of "Auto-detect". |
| Piper Preview falls back to Android TTS | Check the card — voice must show ✓ installed. If installed but still falls back, the native lib isn't loaded (see first row). |
| Whisper capture hangs for 15 s regardless of speech | Amplitude VAD threshold may be too low for your room noise. Raise `AmplitudeVad.rmsThreshold` from the default `0.01f` — room tone > 0.01 will prevent endpoint detection. |
| Piper sample plays at wrong pitch / speed | Sample-rate mismatch. `PiperCppBridge.sampleRate()` should return 22050 for medium voices; if it returns 0, the voice isn't loaded. |

## Related

- [phase-16-native-plan.md](phase-16-native-plan.md) — design + checklists for each native engine
- [real-device-smoke-test.md](real-device-smoke-test.md) — the broader wake→STT→LLM→TTS smoke checklist
- [latency-budgets.md](latency-budgets.md) — per-span budgets the amplitude VAD aims to hit
