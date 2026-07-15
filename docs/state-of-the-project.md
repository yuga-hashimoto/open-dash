# State of the Project

_Snapshot: 2026-07-15._ A maintainer's stake in the ground: what actually works
today, what is scaffolding, and what has not been touched. Updated when the
picture shifts meaningfully — not on every PR.

For the product-level verdict and release gates, see the [smart-speaker audit](smart-speaker-audit.md).

## What the app is

OpenDash is an Android tablet app that behaves as a smart speaker and
an on-device LLM agent with OpenClaw-class tool use. The voice pipeline — wake
word, STT, routing, TTS — runs on-device wherever possible, and the default
assistant provider is a local LiteRT-LM model. Cloud providers exist behind the
same abstraction but are opt-in; nothing in the happy path requires network
access beyond smart-home bridges the user chooses to configure.

## Feature matrix

Legend: ✅ shipped · 🟡 scaffolding / partial · ❌ not started · 🚫 won't do

### Voice pipeline

| Feature | Status | Notes |
|---|---|---|
| Wake word (Vosk) | ✅ | Default always-on path via foreground service. |
| Android STT (`SpeechRecognizer`) | ✅ | Primary recogniser; fast on Pixel-class tablets. |
| Offline STT (Whisper) | 🟡 | Whisper JNI is built and routed when the native/model gates are open; batch transcription and real-device accuracy/latency remain unverified. Settings Voice Health reports this gate via `OfflineVoiceProfile` (not a blanket “offline” claim). |
| VAD parameters exposed | ✅ | Silence timeout, min-speech tunables in Settings. |
| Silero VAD backend | 🟡 | ONNX model download and auto-selection are shipped; live-room endpoint quality remains unverified. Falls back to amplitude VAD. |
| Android TTS | ✅ | Default output; locale-aware. Reported as **System TTS** in the offline profile — installed engine only, not a guaranteed offline language pack. |
| Piper neural TTS | 🟡 | Voice downloader and Kotlin provider exist, but `piper_jni` is not built; playback falls back to Android TTS. Offline profile never claims FullyOffline without Piper native + voice. |
| Offline voice profile diagnostic | ✅ | Pure `OfflineVoiceProfile.evaluate()` + Voice Health `offline_voice_profile` item: FullyOffline / LocalSTTAndLLMSystemTts / NotReady from component gates. |
| openWakeWord | 🟡 | Opt-in English-only `hey jarvis` path is wired; model and real-room detection are unverified. Vosk remains the default. |

### Assistant + tools

| Feature | Status | Notes |
|---|---|---|
| Local LLM via LiteRT-LM | ✅ | On-device Gemma-class model, hardware-aware init. |
| `AssistantProvider` abstraction + Auto routing | ✅ | Embedded · OpenAI-compatible · OpenClaw. |
| 50+ LLM-callable tools | ✅ | Catalog in [tools.md](tools.md). |
| Fast-path router (30+ matchers) | ✅ | Sub-200ms deterministic path; see [fast-paths.md](fast-paths.md). |
| Skills (25+ bundled + install-from-URL) | ✅ | `SKILL.md` format + skill registry; see [skills.md](skills.md). |
| Routines (Room-backed) | ✅ | Multi-step chains, persistent. |
| Memory (EmbeddingGemma + TF-IDF fallback) | ✅ | `remember` / `recall` / `semantic_memory_search`; embedding retrieval is preferred when the local model is available. |
| Personal knowledge / FAQ | ✅ | `add_knowledge` / `search_knowledge` / `remove_knowledge` persist through Room. Offline search also merges a versioned original starter corpus through SQLite FTS5 with a deterministic Kotlin fallback; a full Wikipedia mirror is intentionally out of scope. |
| RAG (Room + chunked retrieval) | ✅ | TextChunker + TF-IDF over stored docs. |
| Context compaction | ✅ | `ContextCompactor` trims long histories. |
| Proactive suggestions | ✅ | Time + rule based (morning / evening briefings). |
| Agent plan executor | ✅ | Multi-tool chaining via `PlanExecutor`. |

### UI + UX

| Feature | Status | Notes |
|---|---|---|
| Ambient / Home / Settings / Dashboard screens | ✅ | Compose + Material 3, tablet-first. |
| Chat screen + VoiceOrb | ✅ | Streaming rendering + state indicator. |
| Permission onboarding | ✅ | `PermissionCatalog` + guided flow. |
| Dynamic launcher shortcuts | ✅ | "Run morning routine" etc. pinnable. |
| Media queue UI (source picker) | ✅ | Pick-and-play via HA media_player. |
| Queue-by-track media control | 🚫 | HA does not expose per-track queue; out of scope. |
| Voice/power measurement | 🟡 | Bounded in-memory recorder, Voice Health summary, and redacted adb/logcat export are shipped. Physical idle wattage and target-device pass/fail remain open. |
| UI internationalisation | 🟡 | 45 resource directories exist (default + 44 locale directories); key parity is tested, but most non-Japanese locale files still contain English placeholder copy. See [i18n.md](i18n.md). |

### Device + system integration

| Feature | Status | Notes |
|---|---|---|
| Smart home: HA / SwitchBot / Matter / MQTT | 🟡 | Saved settings flow through `DeviceSettingsRepository`; service-owned lifecycle starts discovery; capability validation, lock/unlock confirmation, provider read-back, SwitchBot native IDs, and failure truthfulness are covered in code/tests. Matter cluster dispatch and concrete real-device E2E coverage remain open. |
| Accessibility service (tablet control) | ✅ | `read`, `tap_by_text`, `scroll`, `type` verbs. |
| Notification read + reply | ✅ | `list_notifications`, `clear_notifications`, reply. |
| Device admin `lock_screen` | ✅ | Requires user-enabled device-admin grant. |
| Battery saver + thermal throttle | ✅ | Pipeline self-regulates under load. |

### Multi-room (Phase 17)

| Feature | Status | Notes |
|---|---|---|
| mDNS discovery + HMAC NDJSON bus | ✅ | Broadcast TTS, timer fan-out, session handoff, groups, pairing fingerprint. |
| WebSocket upgrade for bus | ❌ | NDJSON is the shipped transport; WS deferred. |
| Camera QR pair | 🚫 | Would add camera dep; word-phrase pairing ships instead. |
| Media handoff across speakers | 🟡 | `session_handoff` moves conversation only — not audio streams. |

### Quality + validation

| Feature | Status | Notes |
|---|---|---|
| Unit tests | ✅ | `./gradlew test` is the authoritative command; the suite is flavor-expanded, so avoid hard-coding a test count in this snapshot. |
| Instrumented E2E tests | 🟡 | App launch, Hilt, fast path, fake-TTS pipeline, provider, and runtime smoke tests exist under `app/src/androidTest/`; no full microphone/speaker UI flow. |
| Real-device smoke test | ❌ | Checklist exists at [real-device-smoke-test.md](real-device-smoke-test.md); never executed end-to-end. |

## What you can do on a real device today

The short user stories below describe intended paths and tested building blocks;
they are not proof that a configured home works end-to-end. See the [smart-speaker
audit](smart-speaker-audit.md) and the "Open questions" section for runtime and
hardware gates.

- **Say "morning briefing"** — proactive rule fires the briefing bundle
  (weather, calendar, unread notifications) through the fast path.
- **Say "broadcast dinner is ready to all speakers"** — mDNS bus finds peers
  on the LAN, HMAC-signs the NDJSON message, every paired tablet speaks it.
- **Tap a pinned "Run morning routine" shortcut** — dynamic launcher shortcut
  triggers the Room-persisted routine without opening the app.
- **Say "turn on the living room lights"** — the fast-path matcher can resolve
  the request without an LLM round-trip, but provider configuration, discovery,
  native command dispatch, and state confirmation still need the P22 release gate.
- **Say "remember that my daughter's birthday is June 3"** — memory tool
  writes to Room, `semantic_memory_search` recalls it later.
- **Say "install skill from https://..."** — skill registry fetches, parses
  the `SKILL.md`, registers tools, and surfaces the new skill in `<available_skills>`.
- **Say "read the notification from Slack and reply 'on my way'"** — a11y +
  notification-listener handle the read + reply without the user touching
  the screen.
- **Ask an open question that needs reasoning** — router escalates to the
  local LiteRT-LM model; heavy tasks can route to OpenClaw/Hermes if
  configured.

## Where to start reading code

- `app/src/main/java/com/opendash/app/voice/pipeline/VoicePipeline.kt`
  — the main loop (wake → STT → route → TTS).
- `app/src/main/java/com/opendash/app/assistant/router/ConversationRouter.kt`
  — provider selection (Embedded / OpenAI-compat / OpenClaw / Auto).
- `app/src/main/java/com/opendash/app/tool/CompositeToolExecutor.kt`
  — tool dispatch and usage stats wiring.
- `app/src/main/java/com/opendash/app/voice/fastpath/FastPathRouter.kt`
  — sub-200ms deterministic matchers (24+).
- `app/src/main/java/com/opendash/app/multiroom/` — Phase 17 mDNS
  discovery + HMAC NDJSON message bus.
- `app/src/main/java/com/opendash/app/a11y/OpenDashA11yService.kt`
  — tablet-control verbs (read / tap / scroll / type).

Start at `VoicePipeline`, follow the route into `ConversationRouter`, and let
`CompositeToolExecutor` take you into specific tools. For deterministic paths,
`FastPathRouter` is the short-circuit that sits before the LLM.

## Open questions and design tensions

Honest list of places where the picture is messier than the matrix implies.

- **Offline stack completion (Phase 16) is partially unblocked.** Whisper
  STT's JNI layer now compiles (P21.6, `externalNativeBuild` re-enabled) and
  is wired into `WhisperSttProvider`, but has never run on a real device —
  a clean compile only proves the toolchain works, not that transcription
  is correct or fast enough. Piper TTS's native side is still fully
  unwired (P14.9's CMake wiring for piper-phonemize + espeak-ng + ONNX
  Runtime remains unstarted). Vosk (wake word) has been production-shipped
  since Phase 8 and isn't affected by this. “Offline” is now
  **component-specific**: Settings Voice Health surfaces
  `OfflineVoiceProfile` modes (`FullyOffline` only when local STT + local
  LLM + neural TTS are all ready; otherwise `LocalSTTAndLLMSystemTts` or
  `NotReady`). Android system TTS is never labeled fully offline neural TTS.
- **Multi-room WebSocket upgrade is deferred.** NDJSON over TCP is the happy
  path today. WebSocket was scoped but not prioritised because NDJSON works
  and adds one dependency less.
- **Media handoff across speakers is stubbed.** `session_handoff` moves the
  conversation context between tablets but does _not_ move audio streams or
  HA media-player state. This is called out because "handoff" is a loaded
  word — today it only means "continue talking to me on the other tablet."
- **No real-device validation has happened.** The JVM/Gradle test suite is green
  and lint is clean, but the [real-device smoke test](real-device-smoke-test.md)
  checklist has never been run end-to-end on hardware. Code correctness is
  evidenced; behavioural correctness on a physical tablet is not.
- **Smart-home runtime wiring is not release-ready.** Configuration injection,
  service-owned discovery, native SwitchBot IDs, capability validation,
  lock/unlock confirmation, provider state read-back, fast-path failure speech,
  and a fail-closed Matter dispatch boundary are implemented. Treat the matrix
  as partial until native Matter cluster wiring and the physical-device
  acceptance gates are closed.
- **Measurement is now observable but not validated.** The recorder captures
  bounded counters, latency, battery percentage, and thermal names without
  transcripts or secrets. It is intentionally not a wattmeter and does not
  replace the dated physical smoke run.
- **The E2E layer is intentionally narrow.** Instrumented tests cover Android
  boot/DI and fake provider paths, but there is no full Compose settings flow,
  microphone/speaker loop, Macrobenchmark, or physical-device test. See
  [e2e-testing.md](e2e-testing.md) and the [smart-speaker audit](smart-speaker-audit.md).
- **External service review.** Several providers (OpenAI-compatible, OpenClaw
  gateway, HA cloud) require user-supplied credentials and are opt-in.
  `RemoteDataPolicy` now gates the first remote assistant turn and provides a
  persistent local-only switch; the physical first-use presentation is still
  unverified.

## Updating this page

When the state of a feature genuinely moves — shipped, un-shipped, scope cut,
scope added — update the matrix and bump the snapshot date at the top. Do not
update it for every PR; this is a trailing indicator, not a changelog.
