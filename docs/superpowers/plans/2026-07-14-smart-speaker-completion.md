# Smart Speaker Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every remaining implementation-level smart-speaker release gap identified by the 2026-07-14 audit, while marking physical-device-only evidence separately.

**Architecture:** Keep `VoiceService` as the application-owned lifecycle for wake word, provider readiness, and device readiness. Add small testable policy/state components instead of putting recovery and authorization rules directly into Compose or Android service code. Preserve the existing provider abstractions; Matter remains an explicit unsupported provider until a real cluster transport is available.

**Tech Stack:** Kotlin 2.1, coroutines/Flow, Jetpack Compose, Hilt, DataStore/SecurePreferences, JUnit 5, MockK.

## Global Constraints

- Do not modify the llama.cpp, whisper.cpp, or piper submodules.
- Do not add dependencies or external API services.
- Do not hardcode secrets; use `SecurePreferences`.
- Add UI strings to every shipped locale when introducing user-visible copy.
- Write tests first and run `./gradlew test` after implementation.
- Physical acoustics, battery, OEM behavior, and real Matter hardware must be documented as unverified unless actually measured.

### Task 1: Provider readiness and voice-service health contract

**Files:** `ProviderManager.kt`, `VoiceService.kt`, `OpenDashApp.kt`, readiness tests, `docs/roadmap.md`.

- [ ] Add a `StateFlow<ProviderReadiness>` with `Starting`, `Ready`, and `Degraded(reason)` states.
- [ ] Make initialization idempotent and awaitable; initialize from the application/service lifecycle, not only `MainActivity`.
- [ ] Make voice entry points wait for provider and device readiness, with a spoken degraded response when no provider is usable.
- [ ] Add unit tests for cold boot, API mode, local model unavailable, and repeated initialization.

### Task 2: Wake-word recovery and degraded-mode fallback

**Files:** `WakeWordDetector.kt`, Vosk/openWakeWord detectors, `VoiceService.kt`, `ModeScaffold.kt`, `ModeScaffoldViewModel.kt`, localized strings/tests.

- [ ] Add a detector health state that distinguishes listening, paused, unavailable, and failed.
- [ ] Restart failed detectors with bounded backoff and cancel the watcher when a new detector/session supersedes it.
- [ ] Expose `hotwordAvailable` and a failure reason to the ambient shell.
- [ ] Restore the mic Talk action when hotword is unavailable or failed; keep it reachable in ambient mode.
- [ ] Add unit tests for detector failure, recovery scheduling, and fallback visibility.

### Task 3: STT error contract and recovery copy

**Files:** `VoicePipeline.kt`, `ErrorClassifier.kt`, `ChatViewModel.kt`, resource strings/tests.

- [ ] Classify no-match as quiet retry, but expose actionable copy for permission, microphone, recognizer, and network failures.
- [ ] Ensure every error returns audio focus, resumes the wake-word path when possible, and reaches a visible `Error` state.
- [ ] Add tests for each error class and the transition back to idle/listening.

### Task 4: Smart-home authorization and read-back contract

**Files:** `DeviceManager.kt`, `DeviceToolExecutor.kt`, device models/providers/tests, docs.

- [ ] Add an explicit command outcome distinction for accepted, rejected, unsupported, and unconfirmed actions.
- [ ] Require confirmation for lock/unlock and other sensitive actions before dispatch; do not claim success before acceptance.
- [ ] Add optional provider read-back for providers that can support it without weakening provider abstractions.
- [ ] Add tests for unauthorized sensitive commands, rejected commands, and read-back failure.
- [ ] Keep Matter truthful: return unsupported until a real cluster dispatcher exists.

### Task 5: Remote-data disclosure and offline-profile truth

**Files:** provider routing/settings UI, privacy copy/resources, `docs/privacy.md`, `docs/state-of-the-project.md`, tests.

- [ ] Add a persisted first-remote-use disclosure state and local-only routing guard.
- [ ] Surface the active local/remote boundary in the voice/ambient status path.
- [ ] Ensure the offline profile is described as offline STT + system TTS until Piper native inference is actually packaged.
- [ ] Add tests for first remote turn, local-only mode, and disclosure persistence.

### Task 6: Release evidence and documentation synchronization

- [ ] Add diagnostic/readiness fields needed for physical smoke runs without claiming the run occurred.
- [ ] Update the audit, roadmap, provider docs, real-device smoke test, and state-of-project matrix from verified behavior.
- [ ] Run `git diff --check`, `./gradlew test`, `./gradlew :app:assembleStandardDebug`, and `./gradlew :app:lintStandardDebug`.
- [ ] Record remaining physical-only blockers explicitly: far-field accuracy, barge-in latency, idle wattage, OEM boot policy, and real Matter hardware.
