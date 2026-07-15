# Smart Home Runtime Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the voice smart-home path use persisted provider settings, discover devices from the service lifecycle, reject unsupported commands honestly, and never speak a success confirmation for a failed command.

**Architecture:** Add one application-scoped `DeviceSettingsRepository` that exposes the current HA, SwitchBot, and MQTT configuration from DataStore/SecurePreferences. Provider clients resolve that repository at operation time, so Settings changes do not require hardcoded DI values. `DeviceManager` becomes an idempotent discovery coordinator owned by `VoiceService`; command capability validation and truthful results remain in the manager/provider boundary.

**Tech Stack:** Kotlin 2.1, coroutines/Flow, Hilt, DataStore, SecurePreferences, JUnit 5, MockK, MockWebServer.

## Global Constraints

- Do not modify `app/src/main/cpp/llama.cpp/`, `app/src/main/cpp/whisper.cpp/`, or `app/src/main/cpp/piper/`.
- Do not add dependencies.
- Preserve `AssistantProvider` and `DeviceProvider` abstractions.
- Write the failing test before each production behavior change.
- Keep existing uncommitted documentation changes intact.
- Matter must return an explicit unsupported result until a real cluster-command implementation exists; do not simulate success.
- Run the focused tests after each task and `./gradlew test` before completion.

### Task 1: Runtime settings repository

**Files:**
- Create: `app/src/main/java/com/opendash/app/device/settings/DeviceSettingsRepository.kt`
- Test: `app/src/test/java/com/opendash/app/device/settings/DeviceSettingsRepositoryTest.kt`
- Read: `app/src/main/java/com/opendash/app/data/preferences/AppPreferences.kt`, `PreferenceKeys.kt`, `SecurePreferences.kt`

- [x] Add tests for blank/default settings, persisted HA URL/token, SwitchBot token/secret, and MQTT broker/username/password.
- [x] Run the focused test and verify it fails because the repository does not exist.
- [x] Implement immutable snapshots and suspend `snapshot()` access using existing preference stores.
- [x] Run the focused test and verify it passes.

### Task 2: Connect provider clients to persisted configuration

**Files:**
- Modify: `app/src/main/java/com/opendash/app/di/HomeAssistantModule.kt`
- Modify: `app/src/main/java/com/opendash/app/homeassistant/client/HomeAssistantRestClient.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/switchbot/SwitchBotApiClient.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/mqtt/MqttClientWrapper.kt`
- Modify: `app/src/main/java/com/opendash/app/di/DeviceModule.kt`
- Test: existing provider/client tests plus focused new tests where no seam exists

- [x] Add failing tests proving HA requests use the repository snapshot rather than the hardcoded URL/token.
- [ ] Add failing tests proving SwitchBot requests use persisted credentials.
- [ ] Add failing tests proving MQTT connect uses the persisted broker and credentials.
- [x] Implement the smallest constructor/config-provider changes; retain existing public provider abstractions.
- [x] Make MQTT `connect()` suspend only if required by the repository snapshot read, and update callers/tests together.
- [x] Run focused provider tests and verify all pass.

### Task 3: Discovery lifecycle and service ownership

**Files:**
- Modify: `app/src/main/java/com/opendash/app/device/DeviceManager.kt`
- Modify: `app/src/main/java/com/opendash/app/service/VoiceService.kt`
- Modify: `app/src/main/java/com/opendash/app/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/opendash/app/ui/devices/DevicesViewModel.kt`
- Test: `app/src/test/java/com/opendash/app/device/DeviceManagerTest.kt`

- [x] Add failing tests that `refreshAll()` invokes `discover()`, `start()` is idempotent, and `stop()` does not leave duplicate collectors.
- [x] Implement provider discovery on refresh, guarded state collectors, and restart-safe lifecycle management.
- [x] Inject/start `DeviceManager` from `VoiceService` and remove UI lifecycle code that can stop the singleton while voice remains active.
- [x] Add a readiness-safe first refresh before voice tools can dispatch commands.
- [x] Run focused manager/service-compilation tests.

### Task 4: Provider command truthfulness and native IDs

**Files:**
- Modify: `app/src/main/java/com/opendash/app/device/provider/switchbot/SwitchBotDeviceProvider.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/mqtt/MqttClientWrapper.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/mqtt/MqttDeviceProvider.kt`
- Modify: `app/src/main/java/com/opendash/app/device/provider/matter/MatterDeviceProvider.kt`
- Test: provider tests under `app/src/test/java/com/opendash/app/device/provider/`

- [x] Add failing tests for SwitchBot prefixed-id to raw-API-id conversion.
- [ ] Add failing tests for MQTT publish failure returning `CommandResult(false)`.
- [x] Add failing tests that Matter command execution returns an explicit unsupported result and does not mutate state.
- [x] Implement the minimal truthful behavior without adding a Matter dependency.
- [x] Run focused provider tests.

### Task 5: Capability authorization and fast-path confirmation

**Files:**
- Modify: `app/src/main/java/com/opendash/app/device/DeviceManager.kt`
- Modify: `app/src/main/java/com/opendash/app/voice/pipeline/VoicePipeline.kt`
- Test: `app/src/test/java/com/opendash/app/device/DeviceManagerTest.kt`
- Test: existing fast-path/pipeline tests under `app/src/test/java/com/opendash/app/voice/`

- [ ] Add failing tests mapping every supported action family to its declared capability; the current slice covers the core rejection seam.
- [x] Add a failing fast-path policy test proving failed tool results cannot use the canned confirmation.
- [x] Implement centralized capability validation and reorder the fast-path result branches so success confirmation is reachable only after `result.success`.
- [x] Run focused tests and then the full `./gradlew test`.

### Task 6: Documentation and verification

**Files:**
- Modify: `docs/smart-speaker-audit.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/state-of-the-project.md`
- Modify: `docs/providers.md`

- [x] Update P22 statuses and provider documentation only from verified implementation behavior.
- [x] Run `git diff --check`, `./gradlew test`, and inspect the final diff for accidental changes to user-owned documentation.
- [x] Report remaining Matter implementation and physical-device gates explicitly.
