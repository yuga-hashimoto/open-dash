# Smart Speaker Audit and Documentation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish whether OpenDash currently behaves as a dependable smart speaker, record evidence-backed gaps, and make the project documentation agree with the actual implementation and validation state.

**Architecture:** Audit the complete voice lifecycle as one product surface: service start → wake-word detection → listening/STT → fast path or provider routing → tools → TTS → interruption/recovery. Separate implementation evidence from real-device proof, then make `docs/smart-speaker-audit.md` the canonical gap report and update the roadmap, project snapshot, and smoke-test documents to link back to it.

**Tech Stack:** Kotlin 2.1, Android foreground services, Compose/Material 3, coroutines/Flow, Room/DataStore, Gradle, Markdown documentation.

## Global Constraints

- Do not modify `app/src/main/cpp/llama.cpp/`, `app/src/main/cpp/whisper.cpp/`, or `app/src/main/cpp/piper/`.
- Do not add dependencies.
- Do not claim real-device behavior from unit tests or clean compilation.
- Preserve the existing priority order: smart-speaker feel is Priority 1.
- All documentation claims must distinguish shipped, opt-in, fallback, unverified, and not implemented.
- Run `./gradlew test` after documentation changes as required by the repository instructions.

### Task 1: Build the evidence table

**Files:**
- Read: `app/src/main/java/com/opendash/app/service/VoiceService.kt`
- Read: `app/src/main/java/com/opendash/app/voice/pipeline/VoicePipeline.kt`
- Read: `app/src/main/java/com/opendash/app/voice/stt/**`
- Read: `app/src/main/java/com/opendash/app/voice/tts/**`
- Read: `app/src/main/java/com/opendash/app/voice/wakeword/**`
- Read: `app/src/main/java/com/opendash/app/device/**`
- Read: `app/src/main/AndroidManifest.xml`
- Read: relevant tests under `app/src/test/` and `app/src/androidTest/`

- [x] Trace the lifecycle and record exact file/line evidence for each stage.
- [x] Mark each stage as implemented, opt-in, fallback, unverified, or missing.
- [x] Record documentation contradictions separately from product gaps.

### Task 2: Write the canonical audit

**Files:**
- Create: `docs/smart-speaker-audit.md`

- [x] State the verdict in one sentence and define the release-gate rubric.
- [x] Add a lifecycle table covering wake, STT, VAD, routing, tools, TTS, barge-in, recovery, boot/background, privacy, and power.
- [x] Add P0/P1/P2 findings with evidence and a concrete acceptance test for each.
- [x] Add explicit limits: no physical-device run, no acoustic accuracy proof, no thermal/power measurement, and no full UI-flow automation.

### Task 3: Reconcile existing documentation

**Files:**
- Modify: `docs/roadmap.md`
- Modify: `docs/state-of-the-project.md`
- Modify: `docs/real-device-smoke-test.md`
- Modify: `docs/offline-stack-smoke-test.md`
- Modify: `docs/phase-16-native-plan.md`
- Modify: `docs/index.md`

- [x] Add the audit as the current Priority-1 release gate and split implementation completion from device validation.
- [x] Correct stale claims about native builds, Silero VAD, openWakeWord, multi-room, E2E tests, locale count, and Piper.
- [x] Make smoke tests executable against the current product, including explicit expected failures where a capability is still absent.
- [x] Link the canonical audit from the docs landing page and related pages.

### Task 4: Verify the documentation change

**Files:**
- Read: all modified Markdown files
- Read: `git diff --check` output

- [x] Run `git diff --check`.
- [x] Run `./gradlew test`.
- [x] Re-scan modified docs for stale phrases; remaining matches are explicitly historical notes or active future work.
- [x] Report any runtime validation still blocked by hardware or credentials without downgrading the finding.
