# Final Smart-Speaker Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete every remaining code-completable smart-speaker gap and turn physical/native blockers into executable acceptance lanes with no misleading readiness claims.

**Architecture:** Add an offline knowledge corpus and indexed search behind the existing `KnowledgeStore`, add deterministic device-side measurement/acceptance tooling, and harden the Matter/Piper integration gates so unavailable native capabilities are explicit and testable. Native submodules and external dependencies remain untouched unless already present in the repository.

**Tech Stack:** Kotlin, Room, SQLite/Room FTS, Coroutines, existing Hilt graph, Android service/adb diagnostics, existing Piper and ONNX Runtime source trees, JUnit 5, Truth.

## Global Constraints

- Do not modify `app/src/main/cpp/llama.cpp/`, `app/src/main/cpp/whisper.cpp/`, or `app/src/main/cpp/piper/`.
- Do not add Gradle dependencies without separate user approval.
- Do not hardcode secrets or store transcripts in measurement output.
- Preserve `AssistantProvider` and `DeviceProvider` abstractions.
- Use TDD and run `./gradlew test` after implementation.
- Do not claim physical-device, Matter commissioning, or wattage validation from JVM/build tests.
- Preserve unrelated dirty-worktree changes; do not commit/push without explicit request.

---

### Task 1: Offline knowledge corpus and indexed search

**Files:**
- Create: `app/src/main/java/com/opendash/app/tool/info/OfflineKnowledgeCorpus.kt`
- Create: `app/src/main/java/com/opendash/app/tool/info/OfflineKnowledgeIndex.kt`
- Create: `app/src/main/java/com/opendash/app/tool/info/SqliteFts5KnowledgeIndex.kt`
- Create: `app/src/main/java/com/opendash/app/tool/info/OfflineKnowledgeStore.kt`
- Create: `app/src/test/java/com/opendash/app/tool/info/OfflineKnowledgeStoreTest.kt`
- Modify: `app/src/main/java/com/opendash/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/opendash/app/di/DatabaseModule.kt`
- Modify: `app/src/main/java/com/opendash/app/di/DeviceModule.kt`
- Modify: `docs/roadmap.md`
- Modify: `docs/state-of-the-project.md`

**Interfaces:**
- Consumes: `KnowledgeStore`, `KnowledgeEntry`, Room `AppDatabase`, and a versioned built-in corpus.
- Produces: offline search that merges durable user entries with bundled corpus entries and never requires network access.

- [x] **Step 1: Write failing tests** for corpus availability, deterministic results, user-entry precedence, limit enforcement, and empty-query behavior.
- [x] **Step 2: Run the focused test** with `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.tool.info.OfflineKnowledgeStoreTest' --console=plain`; expected failure because the FTS/store classes do not exist.
- [x] **Step 3: Implement the corpus and FTS-backed index** using the existing Room database's `SupportSQLiteDatabase` boundary; seed a small, versioned, original core corpus and keep personal rows separate. Use the Kotlin fallback if the device SQLite build rejects FTS5.
- [x] **Step 4: Wire the store into the existing knowledge tool** without changing tool names or output JSON; prefer user-authored rows over bundled rows for equal relevance.
- [x] **Step 5: Run focused tests, `git diff --check`, and update docs** to distinguish bundled corpus completion from physical/offline voice gates.

### Task 2: Physical acceptance runner and measurement contract

**Files:**
- Create: `app/src/main/java/com/opendash/app/voice/diagnostics/VoiceAcceptanceRun.kt`
- Create: `app/src/test/java/com/opendash/app/voice/diagnostics/VoiceAcceptanceRunTest.kt`
- Modify: `app/src/main/java/com/opendash/app/service/VoiceService.kt`
- Modify: `app/src/main/java/com/opendash/app/voice/diagnostics/VoiceMeasurementRecorder.kt`
- Modify: `docs/real-device-smoke-test.md`
- Create: `docs/smoke-runs/README.md`

**Interfaces:**
- Consumes: the existing bounded recorder and explicit operator event actions.
- Produces: a deterministic checklist state machine that reports observed values separately from operator-verified pass/fail.

- [x] **Step 1: Write failing pure tests** for run reset, required step ordering, duplicate/out-of-order events, and final incomplete/complete status.
- [x] **Step 2: Run the focused test** with `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.voice.diagnostics.VoiceAcceptanceRunTest' --console=plain`; expected failure.
- [x] **Step 3: Implement the pure acceptance state machine** with no audio/network/device assumptions and no transcript fields.
- [x] **Step 4: Add service actions and adb commands** for start, step event, operator verdict, export, and clear; keep all actions idempotent and best-effort.
- [x] **Step 5: Run focused tests and document the exact physical-run artifact format** under `docs/smoke-runs/`.

### Task 3: Native integration gates and diagnostics

**Files:**
- Create: `app/src/main/java/com/opendash/app/device/provider/matter/MatterRuntimeStatus.kt`
- Create: `app/src/test/java/com/opendash/app/device/provider/matter/MatterRuntimeStatusTest.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/matter/MatterDeviceProvider.kt`
- Modify: `app/src/main/java/com/opendash/app/voice/diagnostics/VoiceDiagnostics.kt`
- Modify: `docs/providers.md`
- Modify: `docs/phase-16-native-plan.md`

**Interfaces:**
- Consumes: `MatterCommandDispatcher`, `PiperCppBridge`, model/voice download state, and existing provider diagnostics.
- Produces: explicit native readiness categories and actionable next steps; no reflective success path and no native-submodule edits.

- [x] **Step 1: Write failing tests** for Matter unavailable/available/accepted-unconfirmed states and Piper native/model/voice combinations.
- [x] **Step 2: Run focused tests** with `./gradlew testStandardDebugUnitTest --tests 'com.opendash.app.device.provider.matter.MatterRuntimeStatusTest' --tests 'com.opendash.app.voice.diagnostics.*' --console=plain`; expected failure.
- [x] **Step 3: Implement pure status mapping** and add the status to provider documentation; existing Voice Health offline profile remains the Piper status surface.
- [x] **Step 4: Verify the existing Gradle dependency graph**; the Matter native cluster SDK is not present, so the dispatcher remains unavailable and the dependency gate is documented rather than added implicitly.
- [x] **Step 5: Run focused tests and inspect that no submodule file changed.**

### Task 4: Final verification and handoff

**Files:**
- Modify: `docs/roadmap.md`
- Modify: `docs/smart-speaker-audit.md`
- Modify: `docs/state-of-the-project.md`

- [x] **Step 1: Re-read each acceptance item and mark only evidence-backed items complete.**
- [x] **Step 2: Run `git diff --check`.**
- [x] **Step 3: Run `./gradlew test assembleDebug lint --console=plain`.**
- [x] **Step 4: Report the exact remaining physical/native gates and the commands/artifacts that close them.**
