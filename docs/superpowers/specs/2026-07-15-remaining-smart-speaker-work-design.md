# Remaining Smart-Speaker Work Design

## Goal

Close the remaining code-completable reliability gaps in OpenDash while keeping
physical-device evidence, new native dependencies, and risky LiteRT-LM changes
explicitly separate from implementation completion.

## Scope

### Included in this cycle

1. Make personal knowledge persistent in Room. Keep the generic Wikipedia-lite
   corpus as a separate data-acquisition task rather than pretending a small
   seed list is equivalent to it.
2. Add an explicit offline voice-profile diagnostic that reports the real STT,
   LLM, and TTS gates instead of implying that every selectable backend is
   fully offline.
3. Add bounded voice/power measurement instrumentation and a documented export
   path for the physical smoke run.
4. Strengthen the device-provider contract and Matter integration boundary so
   unsupported native dispatch is diagnosed and tested without optimistic
   success.
5. Synchronize roadmap, state-of-project, privacy, and real-device documents.

### Explicitly excluded

- Piper phonemizer/espeak-ng/ONNX Runtime Android port. It requires new native
  sources and dependencies and remains P14.9/P16.3.
- LiteRT-LM incremental tool-result re-entry. The existing full-conversation
  rebuild protects against a previously observed native crash and remains a
  hardware-dependent design change.
- Claiming physical wake accuracy, barge-in, power, locked-screen, Matter, or
  OEM behavior as verified without a dated device run.
- New Gradle dependencies unless the user separately approves them.

## Architecture

The change is split into independent boundaries:

- `RoomKnowledgeStore` implements the existing `KnowledgeStore` contract. A
  `KnowledgeEntity`/`KnowledgeDao` pair owns durable personal entries. The
  generic Wikipedia-lite corpus remains a separate data/licensing and asset
  task; it is not silently represented by personal FAQ rows.
- `OfflineVoiceProfile` is a pure value model and evaluator. It reports each
  gate independently: local STT native library/model, local LLM readiness, and
  local TTS native/voice readiness. `VoiceDiagnostics` adapts the result for
  Settings while preserving actionable failure reasons.
- `VoiceMeasurementRecorder` records bounded, opt-in session samples and
  exports a redacted text/JSON report. It does not claim wattage; battery and
  thermal values are observations that must be collected on hardware.
- `MatterCommandDispatcher` is an injected boundary for native Matter cluster
  dispatch. The provider uses it when available and fails closed with a
  diagnostic result otherwise. JVM tests use a fake dispatcher; no SDK is
  silently mocked as a successful real device command.

## Error and privacy rules

- Knowledge writes are local and durable; no remote fallback is implicit.
- Offline status must say exactly which component is unavailable.
- Measurement reports contain timestamps, device/build metadata, battery and
  thermal observations, and latency counters only; no transcript, API key, or
  device secret is stored.
- Provider command failures remain failures. Accepted-but-unconfirmed remains
  distinct from confirmed state.

## Verification target

- Unit tests for Room mapping/store behavior, offline-profile combinations,
  measurement redaction/aggregation, and Matter dispatch outcomes.
- Full `./gradlew test`, `assembleStandardDebug`, and `lintStandardDebug`.
- Updated physical smoke procedure with explicit commands for exporting the
  measurement report and recording real-device acceptance evidence.
