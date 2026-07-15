# Remaining Smart-Speaker Work Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining code-completable smart-speaker reliability gaps while keeping native-port and physical-device gates explicit.

**Architecture:** Add durable Room-backed knowledge behind the existing `KnowledgeStore`, expose a pure offline-profile evaluator and a privacy-safe bounded measurement recorder, and isolate native Matter cluster dispatch behind an injected interface. Update diagnostics and documentation only from verified code/test evidence.

**Tech Stack:** Kotlin, Coroutines/Flow, Room, Jetpack Compose diagnostics integration, existing Android battery/thermal APIs, JUnit 5, Truth, MockK, existing Hilt modules.

## Global Constraints

- Do not modify `app/src/main/cpp/llama.cpp/`, `app/src/main/cpp/whisper.cpp/`, or `app/src/main/cpp/piper/`.
- Do not add Gradle dependencies without separate user approval.
- Do not hardcode secrets; measurements must exclude transcript/API-key data.
- Preserve `AssistantProvider` and `DeviceProvider` abstractions.
- Use TDD and run `./gradlew test` after implementation.
- Do not claim physical-device behavior from JVM tests.
- Preserve unrelated dirty-worktree changes and do not commit/push without explicit request.

---

### Task 1: Durable personal knowledge store

**Files:**
- Create: `app/src/main/java/com/opendash/app/data/db/KnowledgeEntity.kt`
- Create: `app/src/main/java/com/opendash/app/data/db/KnowledgeDao.kt`
- Create: `app/src/main/java/com/opendash/app/tool/info/RoomKnowledgeStore.kt`
- Create: `app/src/test/java/com/opendash/app/tool/info/RoomKnowledgeStoreTest.kt`
- Modify: `app/src/main/java/com/opendash/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/opendash/app/di/DatabaseModule.kt`
- Modify: `app/src/main/java/com/opendash/app/di/DeviceModule.kt`
- Modify: `app/src/main/java/com/opendash/app/tool/info/KnowledgeToolExecutor.kt`

**Interfaces:**
- Consumes: existing `KnowledgeStore` and `KnowledgeEntry`.
- Produces: `KnowledgeDao` CRUD/search methods and a singleton `RoomKnowledgeStore` implementing `KnowledgeStore`.

- [ ] **Step 1: Write failing store tests**

Test that `add()` assigns an id, `search()` ranks matching question/answer/tags, `remove()` is durable, and a second store instance reads the same fake DAO rows.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.tool.info.RoomKnowledgeStoreTest' --console=plain`

Expected: compilation failure because the Room entity/DAO/store do not exist.

- [ ] **Step 3: Implement Room mapping and query path**

Use a Room entity with `id` as the primary key, `question`, `answer`, `tagsJson`, and `createdAtMs`. Keep JSON serialization at the store boundary with the existing Moshi instance or a small deterministic comma-safe encoding already used by the project. Query candidate rows locally, score terms in Kotlin using the existing `InMemoryKnowledgeStore` semantics, and return only positive matches.

- [ ] **Step 4: Wire the database and production tool executor**

Add the entity/DAO to `AppDatabase`, increment the database version, provide the DAO from `DatabaseModule`, and replace `InMemoryKnowledgeStore()` in `DeviceModule` with the singleton Room implementation. Do not change tool names or result JSON.

- [ ] **Step 5: Run focused tests**

Run the focused test command again. Expected: PASS, including add/search/remove persistence behavior.

### Task 2: Offline voice profile truthfulness

**Files:**
- Create: `app/src/main/java/com/opendash/app/voice/diagnostics/OfflineVoiceProfile.kt`
- Create: `app/src/test/java/com/opendash/app/voice/diagnostics/OfflineVoiceProfileTest.kt`
- Modify: `app/src/main/java/com/opendash/app/voice/diagnostics/VoiceDiagnostics.kt`
- Modify: `app/src/main/java/com/opendash/app/ui/settings/VoiceHealthSection.kt`
- Modify: `docs/state-of-the-project.md`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: injected availability/model-path providers for Whisper, embedded LLM, Android TTS, and Piper.
- Produces: `OfflineVoiceProfile.evaluate()` with component-level status and an overall `supportedMode` enum.

- [ ] **Step 1: Write failing pure evaluator tests**

Cover these exact cases: all local gates ready → `FullyOffline`; local STT + local LLM + Android TTS only → `LocalSTTAndLLMSystemTts`; missing local STT → `NotReady`; Piper native unavailable must not be reported as fully offline neural TTS.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.voice.diagnostics.OfflineVoiceProfileTest' --console=plain`

Expected: compilation failure because the evaluator does not exist.

- [ ] **Step 3: Implement the pure evaluator**

Use immutable data classes with `ready`, `label`, and `reason` per component. Treat Android TTS as `SystemTts` and Piper as `NeuralTts`; never infer offline status from the selected preference alone.

- [ ] **Step 4: Adapt VoiceDiagnostics and UI**

Append a single `offline_voice_profile` diagnostic item with actionable copy. Preserve existing microphone/STT/TTS checks and do not initialize asynchronous TTS from the synchronous diagnostic path.

- [ ] **Step 5: Update truth tables and run tests**

Run the focused test, then verify the Settings consumer renders the new item without a missing-string or lint error. Update the state matrix so “offline” is component-specific.

### Task 3: Privacy-safe voice/power measurement recorder

**Files:**
- Create: `app/src/main/java/com/opendash/app/voice/diagnostics/VoiceMeasurementRecorder.kt`
- Create: `app/src/test/java/com/opendash/app/voice/diagnostics/VoiceMeasurementRecorderTest.kt`
- Modify: `app/src/main/java/com/opendash/app/service/VoiceService.kt`
- Modify: `app/src/main/java/com/opendash/app/voice/pipeline/VoicePipeline.kt`
- Modify: `app/src/main/java/com/opendash/app/voice/diagnostics/VoiceDiagnostics.kt`
- Modify: `docs/real-device-smoke-test.md`

**Interfaces:**
- Consumes: turn start/end timestamps, wake/STT/TTS state transitions, battery percent, and thermal status supplied by injected readers.
- Produces: bounded in-memory/session report with `record()`, `snapshot()`, `clear()`, and redacted `exportJson()`/text output.

- [ ] **Step 1: Write failing recorder tests**

Verify latency aggregation, wake/false-wake counters, thermal/battery sample retention, hard sample cap, and export redaction when input text contains transcript/API-key-like strings.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.voice.diagnostics.VoiceMeasurementRecorderTest' --console=plain`

Expected: compilation failure because the recorder does not exist.

- [ ] **Step 3: Implement the bounded recorder**

Store only numeric counters, state names, timestamps, battery percent, thermal status, and device/build identifiers explicitly allowed by the report schema. Cap samples and make export deterministic for test comparison.

- [ ] **Step 4: Wire lifecycle events**

Record wake detection, STT start/final/error, TTS start/stop, and alert-session outcomes at existing lifecycle points. Keep instrumentation best-effort and never block the voice path.

- [ ] **Step 5: Add physical-run instructions and verify**

Document how to start a run, perform the smoke steps, export the report, and interpret “observed” versus “verified.” Run focused tests and `git diff --check`.

### Task 4: Matter dispatch boundary and provider contract

**Files:**
- Create: `app/src/main/java/com/opendash/app/device/provider/matter/MatterCommandDispatcher.kt`
- Create: `app/src/test/java/com/opendash/app/device/provider/matter/MatterCommandDispatcherTest.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/matter/MatterDeviceProvider.kt`
- Modify: `app/src/test/java/com/opendash/app/device/provider/matter/MatterDeviceProviderTest.kt`
- Modify: `docs/providers.md`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: `DeviceCommand`, the commissioned-device registry, and an optional native dispatcher supplied by the Matter integration layer.
- Produces: explicit `CommandResult` outcomes: confirmed, accepted-but-unconfirmed, unsupported, or failed.

- [ ] **Step 1: Write failing provider tests**

Cover successful fake On/Off dispatch updating state, dispatcher failure leaving state unchanged, unsupported actions returning a stable error, and absent dispatcher never claiming success.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.device.provider.matter.MatterDeviceProviderTest' --console=plain`

Expected: the new confirmed/failed contract tests fail against the current hard-coded “not implemented” path.

- [ ] **Step 3: Implement the injected dispatcher boundary**

Keep the current build dependency set unchanged. Use a default unavailable dispatcher and a fake dispatcher in JVM tests. Dispatch only capability-supported commands; update the in-memory state only after the dispatcher reports success. If the native SDK is absent, return a precise unsupported result and expose that as an integration gate.

- [ ] **Step 4: Document the native SDK boundary**

State that Google Home/Matter commissioning and native cluster control require the device-side SDK/runtime and physical acceptance. Do not mark real Matter control complete merely because the fake dispatcher passes.

- [ ] **Step 5: Run provider tests**

Expected: PASS with no optimistic state mutation on failure.

### Task 5: Integration, docs, and full verification

**Files:**
- Modify: `docs/smart-speaker-audit.md`
- Modify: `docs/state-of-the-project.md`
- Modify: `docs/real-device-smoke-test.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/privacy.md` if the measurement disclosure requires it

- [ ] **Step 1: Synchronize status language**

Mark only Room knowledge, offline profile diagnostics, measurement instrumentation, and the Matter boundary as implemented. Keep Piper, real Matter control, physical wake/STT/power, and LiteRT-LM re-entry explicitly open.

- [ ] **Step 2: Run the authoritative verification suite**

Run:

```bash
git diff --check
./gradlew test assembleStandardDebug lintStandardDebug --console=plain
```

Expected: all tasks finish with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Review the final diff**

Check that no submodule changed, no secret or transcript is persisted, no new dependency was added, and the dirty worktree’s unrelated changes remain intact.
