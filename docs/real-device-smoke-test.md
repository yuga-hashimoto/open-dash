---
title: Real-Device Smoke Test
---

# Real-Device Smoke Test Checklist

This is the on-device validation run that must pass before a release cut. It exercises the wake → STT → LLM → TTS pipeline end-to-end on representative hardware. CI cannot run it because it needs a microphone, speakers, and Play-services-free environment.

This checklist is a release gate, not evidence that the gate has already passed.
The current evidence-backed verdict and the list of still-open product gaps live in
[smart-speaker-audit.md](smart-speaker-audit.md). Every completed run must be saved
under `docs/smoke-runs/YYYY-MM-DD.md`.

Reference for the latency budgets below: [fast-paths.md](fast-paths.md), [providers.md](providers.md).

## Hardware under test

The primary target is an Android tablet (≥ 600dp landscape). Validate on at least one of each:

- **Tablet (primary)** — Pixel Tablet, Galaxy Tab S-series, or Lenovo P12 class
- **Phone (secondary)** — Pixel 6+, Galaxy S22+, for fallback use
- **AOSP (optional)** — LineageOS on a tablet without Play Services (offline STT path)

Network states to verify each pass:

| State | What to verify |
|-------|----------------|
| Online | Remote provider Auto routing picks remote for heavy tasks |
| Offline | Auto routing falls back to embedded LLM; ConnectionBadge shows offline |
| Flaky Wi-Fi | Filler phrase plays; no perceived hang |

## Smoke journey

Run the following scripted flow. Stop on first failure; file as a bug with the latency value captured from the System Info screen.

### 1. Cold start → home
- Kill the app; relaunch.
- **Pass**: Home screen renders within 2 s; ambient clock ticks; ConnectionBadge reflects network.

### 2. Wake → listening latency
- Speak the configured wake phrase from 1 m away in a quiet room, ten times.
- **Pass**: all ten attempts are detected, VoiceOrb transitions to `LISTENING` within **500 ms** (P8.2 budget), and there are no false wakes during 5 minutes of silence.
- **Fail trigger**: LatencyRecorder → `WAKE_TO_LISTENING` budget violation in System Info.
- Repeat once with the configured openWakeWord engine if selected. Record the engine,
  model readiness, detections, missed detections, and false wakes separately; do not
  treat Vosk results as evidence for openWakeWord.

### 3. Fast-path command
- Say: "Set a 5 minute timer."
- **Pass**: Spoken confirmation within **200 ms** (FAST_PATH_TO_RESPONSE budget); AmbientScreen shows timer countdown.
- Try the 20+ matchers from [fast-paths.md](fast-paths.md) — at minimum: timer, volume, lights, weather, news, goodnight, morning briefing, help.

### 4. Tool-calling path
- Say: "What's the weather tomorrow in Tokyo?" (skips fast-path, goes through LLM).
- **Pass**: Filler phrase plays within ~1 s; final answer within 3 s on remote provider, 6 s on embedded.

### 5. Barge-in
- Start a long TTS utterance ("Tell me a story about cats").
- Mid-utterance, say the wake phrase.
- **Pass**: TTS halts immediately; VoiceOrb switches to `LISTENING`.
- Record the time from the second wake phrase to `TTS.stop()`. Unit tests cover the
  control path, but only this run can validate room acoustics and echo cancellation.

### 5.1 Alarm/timer wake-word-free stop

P21.5 adds a bounded alert-listening session. While an in-app timer or alarm is
actually ringing, say `止めて`, `キャンセル`, `スヌーズ`, `stop`, or `snooze`
without saying the wake word first. Verify that:

- a single ringing timer stops without cancelling an unrelated timer;
- a single ringing alarm stops or snoozes and the next schedule is preserved;
- multiple ringing alerts produce a clarification request and never cancel an
  arbitrary alert;
- silence/no-match returns to normal wake-word listening after the bounded
  retry budget;
- the microphone is not left open after the alert stops or the retry budget is
  exhausted.

Repeat this with the screen locked and after the app process has been absent
before the alarm fires. Record the device model, Android version, alert type,
command language, time to silence, and whether the next wake-word turn works.
This is a physical-device gate; JVM tests do not prove acoustic recognition or
OEM foreground-service behavior.

### 6. Error recovery
- Disable Wi-Fi and Cellular.
- Ask a question that requires a remote tool (`web_search`, `get_news`).
- **Pass**: Spoken error uses the offline-friendly copy from ErrorClassifier (`LOCAL_ENGINE` or `NETWORK`); app does not crash.
- Re-enable network; same question should now succeed.

### 7. Tablet landscape
- Rotate to landscape on a ≥ 600dp device.
- **Pass**: Two-column layout renders; touch targets ≥ 48 dp; night mode + NightClockOverlay toggles work.

### 8. Permissions walkthrough
- Fresh install (or `pm clear com.opendash.app`).
- After model download finishes, OnboardingScreen must appear before the mode scaffold.
- **Pass**: Each permission row's "Grant" deep-link opens the correct system screen.

### 9. Long-running stability
- Leave the app in ambient mode for ≥ 30 minutes with wake-word listening active.
- **Pass**: No memory growth visible in System Info; wake word still responds; TTS still plays.

### Multi-room discovery (P14.5)

Verifies the shipped mDNS/NSD plumbing and the opt-in HMAC-NDJSON message bus.
Discovery and broadcast are implemented, but still need a two-device physical run.

**Prerequisite**: two devices running the debug build on the same LAN (same Wi-Fi SSID, multicast not blocked by the router — some guest networks drop mDNS).

1. **Discovery from the app**
   - Launch the app on both devices.
   - On device A, open Settings → System Info and watch the **"Nearby speakers (mDNS)"** row.
   - **Pass**: device B (instance name `OpenDash-<Build.MODEL>`) appears in the list within ~3 s of launch. Swap roles and re-verify from device B.
   - **Fail trigger**: row stays on "0 peers" after 10 s — check that both devices are on the same subnet and that `MulticastDiscovery.start()` ran (look for the log tag in `adb logcat | grep MulticastDiscovery`).

2. **Registration from a desktop**
   - From a machine on the same LAN:
     - macOS: `dns-sd -B _opendash._tcp`
     - Linux: `avahi-browse -rt _opendash._tcp`
   - **Pass**: each running instance is listed with its instance name and port `8421` (the `DEFAULT_PORT` from `MulticastDiscovery`).
   - Kill the app on one device; the entry should disappear from the browse output within a few seconds.

#### Multi-room broadcast end-to-end (P17.2 / P17.3)

Validates the NDJSON listener + sender wired up in PR #241 (server), PR #243 (client/broadcaster), and PR #245 (`broadcast_tts` tool + matcher).

**Prerequisite**: two tablets on the same LAN, both running the app, and **both with the same Multi-room shared secret set** in Settings (the new row added in PR #242).

1. On both devices, enable the **Multi-room broadcast** toggle.
2. On device A, say "broadcast dinner is ready to all speakers" — expect device B to speak "dinner is ready" within ~1 s.
3. **Tamper test**: change the secret on device B only, repeat step 2 — device B should stay silent (`AnnouncementParser` rejects on HMAC mismatch). Check `adb logcat` on device B for `Envelope rejected: HMAC_MISMATCH`.
4. **Replay test**: set both clocks, then artificially skew device B's clock 2 minutes forward. Repeat step 2 — device B should stay silent (`Envelope rejected: REPLAY_WINDOW`).
5. **No-secret test**: clear the secret on device A, repeat step 2. Device A's tool should return a spoken message "Broadcast refused: no shared secret."

Reference: see [`multi-room-protocol.md`](multi-room-protocol.md) for the full protocol.

### 10. System info sanity
- Open Settings → System Info.
- **Pass**: Device count, routines count, documents count, latency measurements count all render without errors. Also verify the new **"Nearby speakers (mDNS)"** and **"Thermal state"** rows render with a live value rather than a placeholder.

### 11. Degraded-mode recovery

- Delete or disable the wake-word model, then return to Ambient Home.
- **Pass**: the app explains why hotword is unavailable and exposes a one-tap Talk
  action or an equally obvious recovery path.
- Revoke microphone access or toggle Android's microphone privacy switch while the
  service is running. **Pass**: a visible/spoken error appears and the service either
  recovers automatically or exposes a retry action; it must not remain silently idle.

### 12. Boot limitation and recovery (targetSdk 35)

Android 14+ apps targeting SDK 34+ **cannot** start a microphone-type foreground
service from `BOOT_COMPLETED`. OpenDash targets SDK 35, so `BootReceiver` follows
`BootPolicy`: it **must not** call `VoiceService.start()` on boot. It only re-arms
exact alarms, reminders, and scheduled routines via `BootRescheduler`. Always-on
wake-word returns when the user opens the app (`MainActivity` starts `VoiceService`
from a user-visible activity context).

1. **Pre-reboot setup**
   - Set a one-shot alarm ~3–5 minutes out, a reminder, and (if available) a scheduled routine.
   - Confirm the mic foreground notification is present while the app is open.
2. **Reboot the tablet** (or `adb reboot`). Do **not** open OpenDash yet.
3. **Pass — no illegal mic FGS on boot**
   - Before launching the app: no OpenDash microphone foreground service / wake-word
     listening. `adb logcat` should show boot reschedule logs, not a successful
     `VoiceService` start from `BootReceiver`.
   - Do **not** fail the run if wake-word is quiet until the app is opened — that is
     the compliant recovery path.
4. **Pass — schedule survival**
   - Without relying on wake-word: the pre-set alarm still fires (tone / full-screen
     notification). Reminder and scheduled routine re-arm and fire as expected.
5. **Pass — user-visible resume**
   - Launch OpenDash. `MainActivity` starts `VoiceService`. Mic notification returns;
     wake word responds within the step-2 budget.

## Power & thermal

Run for 15 minutes with wake-word listening active and screen on at 50 % brightness.

- **Thermal**: `adb shell dumpsys thermalservice` should not report STATUS_SEVERE or worse.
- **Battery drain**: record % drop over 15 min and the device charging state. The
  repository has no measured idle target yet, so record the value rather than marking
  it as passing by assumption. (Tracked by P22.8.)

## Voice/power measurement export (instrumentation)

`VoiceMeasurementRecorder` is **measurement instrumentation**, not proof that a
physical budget was met. It stores only counters, state names, timestamps,
battery **percent**, thermal **status names**, and device/build identifiers.
It never stores transcripts, API keys, or secrets, and it never invents wattage
from battery percent.

### Start a clean run

```bash
# App package is com.opendash.app (debug may use a suffix — check with adb shell pm list packages | grep opendash)
adb shell am startservice \
  -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.CLEAR_MEASUREMENT
```

### Perform the smoke steps

Run steps 2–5.1 (wake, fast-path, tool path, barge-in, alert stop) while the
service is up. The recorder is best-effort and must not block STT/TTS.

### Export the redacted report

```bash
adb shell am startservice \
  -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.EXPORT_MEASUREMENT

adb logcat -d | grep -A 200 VoiceMeasurementReport
```

Paste the text block into `docs/smoke-runs/YYYY-MM-DD.md`. JSON is available
from the same in-memory session via `exportJson()` for automated comparison;
both paths redact free-form / API-key-like labels.

### Observed vs verified

| Label | Meaning |
|-------|---------|
| **Observed** | Values present in the export (counts, battery %, thermal name, latency aggregates). Evidence that the run happened and instrumentation fired. |
| **Verified** | Operator judgment against the pass criteria in this document (e.g. all ten wakes within 500 ms). Requires the physical device, room, and operator notes — the export alone is not verification. |

Settings → Voice Health may show a one-line **Voice measurement session** card
(sample/wake/STT/TTS counts). That card is also instrumentation-only.

### Ordered acceptance run

The service also exposes a pure ordered acceptance state machine. Start it
before the smoke journey and record each step in the order below:

```bash
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.START_ACCEPTANCE_RUN \
  --es acceptance_run_id "2026-07-15-device-name"
```

Required step ids are:
`cold_start`, `wake_latency`, `fast_path`, `tool_path`, `barge_in`,
`alert_stop`, `error_recovery`, `tablet_layout`, `permissions`, `stability`,
`system_info`, `degraded_recovery`, and `boot_recovery`.

Record observation and then an explicit operator verdict for each step. The
state machine rejects missing, duplicate, and out-of-order events; observation
alone never becomes a pass.

```bash
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.OBSERVE_ACCEPTANCE_STEP \
  --es step_id wake_latency --es note "10 attempts observed"
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.VERIFY_ACCEPTANCE_STEP \
  --es step_id wake_latency --ez passed true \
  --es note "Operator verified against 500 ms criterion"
adb shell am startservice -n com.opendash.app/.service.VoiceService \
  -a com.opendash.app.EXPORT_ACCEPTANCE_RUN
```

Use [smoke-runs/README.md](smoke-runs/README.md) for the complete artifact
format and keep the acceptance export separate from the numeric measurement
export.

## Recording the run

Save the journey as a dated markdown note under `docs/smoke-runs/YYYY-MM-DD.md`. Include:

- Device model + Android version
- Latency values for step 2 and step 3
- Pass/Fail per step
- Any budget violations copied from System Info
- The `VoiceMeasurementReport` export (or a note that export was skipped)

## When to run

- Before each **tagged release** (P13.4 release workflow).
- After changes to: `VoicePipeline`, `AndroidSttProvider`, `FastPathRouter`, `TtsManager`, `VoskWakeWordDetector`, or anything under `service/`.
