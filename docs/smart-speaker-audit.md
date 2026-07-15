---
title: Smart-speaker product audit
---

# Smart-speaker product audit

_Audit date: 2026-07-14_
_Scope: the shipped Android app, its voice lifecycle, smart-home control path, and the documentation that claims those paths are complete._

## Verdict

OpenDash is a strong voice-first Android agent prototype with most of the required software paths now implemented, but it is not yet a release-ready smart speaker. The architecture covers wake word → STT → routing → tools → TTS, and the code now has readiness, recovery, authorization, and remote-data gates. The decisive product proof is still missing: reliable far-field behavior on a tablet, self-recovery after audio/service failure on target OEMs, and a completed physical-device smoke run.

The most important distinction is:

| State | Meaning in this audit |
|---|---|
| Implemented | The path exists in production code and has focused tests where noted. |
| Opt-in / fallback | The path is not the default smart-speaker experience, or silently degrades to another backend. |
| Unverified | The code compiles or has unit coverage, but no current physical-device evidence proves acoustics, latency, thermal, or OEM behavior. |
| Missing | A user-visible capability or recovery path is absent, not merely undocumented. |

## Product lifecycle assessment

| Stage | Current evidence | Assessment |
|---|---|---|
| Boot and always-on service | `BootReceiver` starts `VoiceService`; `MainActivity` starts it after microphone permission; the service is `START_STICKY` and uses a microphone foreground-service type. | Implemented, but OEM/Android-version survival is unverified. A foreground service starting successfully is not proof that its detector stays alive after Doze, audio focus changes, or process pressure. |
| Wake word | Vosk is the default detector and reads 16 kHz `AudioRecord`; openWakeWord is opt-in and English-only. AEC/noise suppression are best-effort. `VoiceService` now observes detector health and retries with bounded recovery. | Implemented in code, unverified in a real room and across OEM audio failures. |
| User fallback to start a turn | Ambient `ModeScaffold` now exposes a Talk FAB when hotword is disabled, unavailable, or failed, while Chat and Settings retain mic entry points. | Implemented in code, but physical reachability and copy still require device validation. |
| STT | Android `SpeechRecognizer` is the primary backend. Whisper JNI is wired when native/model gates are open; Whisper is batch-only and emits no partials. | Partial. Android STT may be cloud/device-engine dependent; offline Whisper is compile-verified but not accuracy/latency verified on hardware. |
| Endpoint/VAD | Android silence knobs exist; Whisper capture uses amplitude VAD and can auto-upgrade to Silero when its ONNX model is downloaded. | Implemented as layered fallback, but live-room false positives, endpoint timing, and Japanese speech are unverified. |
| Fast path | `VoicePipeline` checks `FastPathRouter` before the LLM for timers, volume, lights, time/date, and other deterministic commands. | Strongest Priority-1 path. Still needs real audio-to-audio timing evidence; the unit budget tests do not measure a microphone or speaker. |
| LLM/provider routing | Local, API-compatible, OpenClaw, and Hermes providers share `AssistantProvider`; `ProviderManager` is app/service initialized, checks actual provider availability, and `VoiceService` waits for readiness. | Implemented in code with a spoken degraded path; cold-boot/OEM behavior remains unverified. |
| Smart-home control | HA, SwitchBot, Matter, and MQTT are behind `DeviceProvider`; runtime settings flow through `DeviceSettingsRepository`, discovery is service-owned, capability checks are centralized, sensitive actions require confirmation, and commands perform provider state read-back. | Runtime wiring and failure truthfulness are implemented. Matter real cluster dispatch and a real device-control acceptance run remain open; providers that cannot read back report accepted-but-unconfirmed. |
| TTS | Android TTS is the default. OpenAI/ElevenLabs/VOICEVOX are selectable; Piper has Kotlin/JNI scaffolding and falls back to Android TTS because its native library is not wired. | Spoken output exists, but “fully offline neural TTS” is not shipped. Android TTS voice availability and offline language packs are external device state. |
| Barge-in | `VoicePipeline` re-arms wake word during TTS and stops TTS when a new turn begins; AEC/NS was added. | Implemented in logic and unit-tested, unverified acoustically. No hard evidence proves wake-word detection wins over the device's own TTS/media output within the target latency. |
| Error recovery | Provider/tool errors are classified and spoken; quiet STT no-match/timeouts return silently, actionable STT failures speak recovery copy, and wake detector health drives bounded restart plus ambient Talk fallback. | Implemented in code, unverified under real audio/service failures. |
| Alarms/reminders/routines | Boot rescheduling, foreground alarm ringing, full-screen notification, snooze/cancel, routine execution, and a bounded wake-word-free alert session exist. | Implemented in code; alarm correctness, false activation behavior, locked-screen behavior, and OEM microphone/foreground-service behavior remain physical-device gates. |
| Power/thermal | Saver state can suppress wake word and shows an ambient chip. A bounded, privacy-safe recorder now exports wake/STT/TTS/latency/battery/thermal observations. | Partial. The recorder is instrumentation, not a wattmeter; the target device still needs a measured always-on power result and operator judgment. |
| Multi-room | mDNS discovery, HMAC-NDJSON bus, broadcast TTS/timers, groups, and pairing are wired behind an opt-in toggle. | Implemented as a network feature, not proof of whole-home speaker parity. Media handoff is conversation-only; audio/media state does not move. |
| Privacy and permissions | Runtime/special permissions and secure secret storage exist; `RemoteDataPolicy` blocks the first remote assistant turn until disclosure acknowledgement and exposes a persistent local-only switch. | Implemented in code; physical first-use presentation and an always-visible remote-active indicator remain product validation work. |

## Findings

### P0 — release gates

These do not all require immediate code changes, but the product should not be called “smart-speaker ready” until they are closed.

1. **No completed physical-device smoke run.** `docs/real-device-smoke-test.md` is a checklist, not evidence. There is no dated `docs/smoke-runs/YYYY-MM-DD.md` result for wake accuracy, wake-to-listening, fast-path audio, barge-in, locked-screen alarm, 30-minute stability, or thermal/power behavior.

   Acceptance: run the checklist on at least one representative tablet with 10 wake attempts, 10 fast-path commands, a long TTS barge-in, offline/online recovery, reboot alarm, and a 30-minute ambient run. Record pass/fail and measured values.

2. **The default always-on path now has bounded self-healing in code.** `VoiceService` observes `WakeWordHealth`, cancels stale detector watchers, and restarts Vosk/openWakeWord after failure with bounded backoff. The remaining gate is reproducing `ERROR_DEAD_OBJECT`, mic privacy toggles, and audio ownership loss on target hardware.

   Acceptance: inject or reproduce `ERROR_DEAD_OBJECT`, recognizer failure, mic privacy toggle, and temporary audio ownership loss; the service must expose a degraded state, retry with backoff, and recover without opening Settings or restarting the app.

3. **The main ambient surface now has a degraded-mode voice action.** `ModeScaffold` shows a Talk FAB when hotword detection is disabled, unavailable, or failed. The remaining gate is verifying the control and reason copy on a real tablet in each degraded state.

   Acceptance: when hotword is unavailable, the ambient surface must show a one-tap “Talk” action and the reason; when available, the same control should remain reachable for users who cannot use a wake word.

4. **The saved provider settings were not reaching the live clients. Resolved in this implementation slice.** `DeviceSettingsRepository` now reads the current DataStore/SecurePreferences snapshot, and Hilt injects it into Home Assistant, SwitchBot, and MQTT clients. The remaining gate is a restart-and-real-device acceptance run proving that Settings changes affect discovery and command traffic.

   Acceptance: after saving each provider in Settings, restart the app and verify the exact URL/credential reaches the provider, that the device list populates, and that clearing the setting disables that provider. Add one runtime/DI test per provider.

5. **Provider command parity is incomplete.** Matter's `executeCommand()` still has no Matter cluster dispatcher and now returns an explicit failure instead of mutating optimistic in-memory state. MQTT discovery is now started through `DeviceManager.refreshAll()`, and SwitchBot strips its app-only `switchbot_` prefix before API calls. Real outbound-command and state-read-back evidence is still missing.

   Acceptance: for one device per provider, capture the real outbound command and then read back state from the provider. A command must not be reported successful until the provider accepted it, and Matter/MQTT/SwitchBot must use their native IDs/protocols.

6. **Fast-path success speech is fixed and read-back is explicit.** Fast-path confirmations now require a non-failing, confirmed result. `DeviceManager` performs provider-independent post-command state read-back; when a provider accepts a command but cannot return state, the response is explicitly marked accepted-but-unconfirmed.

   Acceptance: force a provider timeout, HTTP 401, missing device, MQTT publish failure, and Matter dispatch failure. The spoken response must be failure/retry copy, never a canned success. For safety-critical actions, require a read-back or an explicit “accepted but not confirmed” message.

7. **VoiceService now owns device-manager lifecycle and provider readiness.** `VoiceService` starts and stops the singleton `DeviceManager`, waits for that initialization on voice entry points, and waits for `ProviderManager` readiness before accepting a voice entry. UI ViewModels no longer stop it during teardown. Cold-boot/OEM behavior is still a release gate.

   Acceptance: start the app/service from a cold boot with no screen navigation, wait for the device-health readiness state, and issue a voice light command. Device discovery must be service-owned or explicitly awaited before advertising the device tool.

### P1 — product reliability gaps

8. **Boot-started VoiceService can now distinguish readiness from service start.** `ProviderManager.initialize()` is called from the application lifecycle and is idempotently awaited by `VoiceService`; actual provider availability is checked before `Ready`, and a degraded state is spoken when no provider is usable. The remaining risk is Android/OEM boot policy and physical cold-boot behavior.

   Acceptance: initialize the provider registry from an application/service-owned lifecycle, or make the service explicitly await a readiness state before accepting a wake. A cold boot with no screen navigation must produce either a working local turn or a spoken readiness/degraded message.

9. **STT error handling is now split by recoverability.** `VoicePipeline` keeps no-match and speech-timeout quiet, while permission, microphone, recognizer, and network-shaped failures receive classified recovery copy before returning to Idle. The remaining gate is physical behavior across Android recognition-service implementations.

   Acceptance: classify no-match as quiet retry, but speak/display actionable copy for microphone blocked, recognizer unavailable, permission denied, and repeated transient failure. Every error must return to a known listening/degraded state.

10. **Capability validation and sensitive confirmation are centralized.** `DeviceManager` rejects unsupported actions and lock/unlock without explicit confirmation. The voice fast path stores a short-lived pending command, asks for yes/no, and only then dispatches; success speech also requires provider read-back.

   Acceptance: reject actions not present in `Device.capabilities`; classify unlock/lock and other sensitive actions separately; require the product's explicit confirmation policy before dispatch and never speak success before the command result is known.

11. **The offline claim is not a single verified product mode.** Whisper native code compiles, but Whisper is batch-only and unverified on-device; Piper remains fallback-only because `piper-phonemize`, `espeak-ng`, and ONNX Runtime Android packaging are not wired. Android TTS may require an installed language voice and may not be offline.

   Acceptance: expose a diagnostic that says exactly which gates are ready, then run a real offline utterance with local STT, local LLM, and a confirmed local TTS voice. If Piper is not available, label the mode “offline STT + system TTS,” not “fully offline voice.”

12. **Barge-in is unit-verified, not room-verified.** The pipeline re-arms the detector before TTS and applies AEC/NS. The new bounded recorder can capture detection, false-wake, missed-wake, and latency observations, but no physical run has supplied the rates or interruption latency while the tablet is speaking.

   Acceptance: measure 20 interruptions at 1 m and 3 m across quiet room, music, and Japanese speech; record TTS stop latency, missed wakes, false wakes, and whether the next STT turn starts cleanly.

13. **Background entry points are not covered as one contract.** `VoiceService.start()` uses `startForegroundService`, while `triggerListening()` and the Settings mic test use `startService`. Quick Settings, media-button, assistant-session, boot, locked-screen, and foreground-activity entry points therefore need an Android-version/OEM test matrix rather than a single “service starts” assumption. In particular, target SDK 35 plus a microphone foreground service started from `BOOT_COMPLETED` is an OS-policy blocker: [Android's current FGS rules](https://developer.android.com/about/versions/15/changes/foreground-service-types) disallow launching a microphone foreground service from `BOOT_COMPLETED` for apps targeting Android 14+, subject to narrow exceptions. Replace it with a compliant boot/resume strategy before claiming reboot-surviving always-on behavior.

   Acceptance: verify every entry point with the app process absent, screen locked, and Android 12–15-class behavior; capture failures and convert any allowed-start exception into a user-visible fallback.

14. **Remote-data disclosure is now part of the voice turn.** `RemoteDataPolicy` blocks an unacknowledged remote provider and local-only mode blocks all remote assistant routing. Settings exposes both controls; physical first-use presentation remains unverified.

   Acceptance: before the first remote turn, show and speak a concise disclosure naming the provider/data boundary, with a persistent setting to force local-only routing.

### P2 — parity and maintainability

15. **Multi-room is not full media handoff.** `session_handoff` transfers conversation context, while audio streams and HA media-player state remain local. Keep the name and user-facing copy explicit.

16. **Power target is not measured.** Battery/thermal gating is a safety response, and the recorder only observes battery percentage and thermal state; it cannot infer watts. Measure wake-word idle drain with external/device power evidence on the target tablet and document the result.

17. **The L3 test layer is narrow.** Instrumented tests exist for app launch, Hilt, fast path, provider behavior, and fake TTS, but there is no automated UI flow for permissions/settings/degraded voice and no real audio loop. The docs must not call the app “no E2E tests” or imply L3 proves the hardware path.

18. **Documentation had multiple contradictory sources of truth.** The project snapshot was dated 2026-04-17; the offline smoke test said native builds and Silero were pending even after they landed; the real-device smoke test said multi-room broadcast was not wired; and the Phase 16 plan still described Whisper CMake as dormant. These are corrected by this audit and the linked updates.

## Recommended order

1. Close the P0 physical-device gate and add a dated run.
2. Run cold-boot readiness and Android/OEM background-entry validation.
3. Run one real provider acceptance per smart-home backend; Matter cluster dispatch remains a separate implementation gap.
4. Define and validate the supported offline profile; keep Piper as a separate native porting project.
5. Measure barge-in, wake accuracy, power, and background entry points on target hardware.
6. Capture the first-use remote-data disclosure and authorization flows on a physical tablet.

## Evidence limits

This audit used current repository code, tests, Gradle configuration, manifest declarations, and Markdown documentation. It did not claim any physical-device result. In particular, it could not validate room acoustics, Japanese/English wake-word accuracy, OEM foreground-service policy, actual TTS voice availability, audio interruption latency, thermal throttling, battery drain, or multi-tablet LAN behavior. Those are release gates, not assumptions that can be filled by another unit test.

## Canonical follow-up documents

- [Roadmap](roadmap.md) — Priority-1 backlog and acceptance gates.
- [State of the project](state-of-the-project.md) — current implementation snapshot.
- [Real-device smoke test](real-device-smoke-test.md) — physical validation procedure.
- [Offline stack smoke test](offline-stack-smoke-test.md) — current Whisper/Silero/Piper gate status.
- [Latency budgets](latency-budgets.md) — instrumentation targets, not proof of hardware performance.
