# Assistant Providers

Each implements `AssistantProvider`. Switch via **Settings → Assistant providers**.

| Provider | Id | Local | Streaming | Tools | Notes |
|---|---|---|---|---|---|
| EmbeddedLlmProvider | `embedded_llm` | yes | yes | yes | LiteRT-LM, GPU→CPU fallback, chosen model via ModelDownloader |
| OpenAiCompatibleProvider | `openai_compatible` | no | yes | yes | `/v1/chat/completions` with SSE |
| AnthropicProvider | `anthropic` | no | yes | yes | Anthropic Messages API, configurable model and token |
| OpenClawProvider | `openclaw` | no | yes | yes | WebSocket, forwards full tool schema |
| HermesAgentProvider | `hermes_agent` | no | yes | yes | HTTP NDJSON, Bearer auth |

## Capabilities

`ProviderCapabilities` carries:
- `supportsStreaming`
- `supportsTools`
- `supportsVision`
- `maxContextTokens`
- `modelName`
- `isLocal` — consumed by `ErrorClassifier` so network errors aren't blamed when a local provider fails

## Routing

`ConversationRouter` picks the active provider via `RoutingPolicy`:
- `Manual(id)` — explicit
- `Auto` — best available (may consult `HeavyTaskDetector`)
- `Failover(ordered)` — try in order
- `LowestLatency` — benchmark

## Speech-to-Text providers

`DelegatingSttProvider` routes `startListening()` to the backend selected in
**Settings → Speech Recognition**. The selection is stored in
`PreferenceKeys.STT_PROVIDER_TYPE` and resolved through `SttProviderType`.

| Provider | Id | Offline | Status | Notes |
|---|---|---|---|---|
| AndroidSttProvider | `android` | no | shipping | `android.speech.SpeechRecognizer`, GMS-backed; default |
| Vosk (offline) | `vosk` | yes | scaffold | `OfflineSttStub`; Vosk is currently the wake-word detector, not the STT backend |
| Whisper (offline) | `whisper` | yes | partial | `WhisperSttProvider` is selected when native library and model gates are open; otherwise it falls back to `OfflineSttStub` |

The offline route is not a release claim until a real-device recording run
proves accuracy, endpointing, latency, and thermal behavior. The current
provider contract is batch transcription with no partial-result guarantee.

## Text-to-Speech providers

`TtsManager` picks the active backend from `PreferenceKeys.TTS_PROVIDER`
(**Settings → Text-to-Speech**). All backends implement `TextToSpeech`.

| Provider | Id | Local | Status | Notes |
|---|---|---|---|---|
| AndroidTtsProvider | `android` | yes | shipping | `android.speech.tts.TextToSpeech`, default |
| OpenAiTtsProvider | `openai` | no | shipping | `/v1/audio/speech`, configurable voice + model |
| ElevenLabsTtsProvider | `elevenlabs` | no | shipping | Cloud neural voice, voice-id + model selectable |
| VoiceVoxTtsProvider | `voicevox` | yes* | shipping | Self-hosted VOICEVOX ENGINE on LAN; Japanese |
| PiperTtsProvider | `piper` | yes | partial | Native bridge and voice downloader exist, but `piper_jni` is not packaged; falls back to Android system TTS today |

\* VOICEVOX runs locally on the user's LAN but requires a separate engine
process (Docker / PC).

The `piper` option is live in Settings, but its offline-neural claim remains
open until the native build, phonemization/runtime dependencies, and playback
are verified on the target tablet.

## Smart-home provider runtime

Home Assistant, SwitchBot, MQTT, and Matter implement `DeviceProvider` and are
coordinated by the singleton `DeviceManager`. HA/SwitchBot/MQTT runtime
configuration is read from `DeviceSettingsRepository` at operation time:

- URLs, broker address, and MQTT username are stored in DataStore.
- HA, SwitchBot, and MQTT secrets are stored in `SecurePreferences`.
- `VoiceService` starts discovery before accepting voice entry points; screen
  ViewModels must not own or stop the manager.
- Matter command delivery goes through an injected `MatterCommandDispatcher`
  boundary. The default build has no Matter cluster SDK dependency; the
  unavailable dispatcher fails closed with a precise unsupported result and is
  an explicit integration gate (`isClusterDispatchAvailable`). JVM tests use a
  fake dispatcher only. In-memory state updates only after the dispatcher
  reports success (confirmed or accepted-but-unconfirmed). Google Home/Matter
  commissioning and native cluster control still require the device-side
  SDK/runtime and physical acceptance — a green fake-dispatcher suite does not
  mean real Matter control is complete.
  `MatterRuntimeStatus` separately reports native-runtime availability,
  commissioned-device count, and the explicit physical-acceptance gate.
- `DeviceManager` validates declared capabilities and requires explicit voice
  confirmation for `lock`/`unlock`. After a successful command it reads the
  provider state back; providers that cannot confirm return an explicit
  accepted-but-unconfirmed result, never a canned success.

Provider setup is still an opt-in Settings flow. A saved credential is not
evidence that a physical device accepted a command; use the [real-device smoke
test](real-device-smoke-test.md) for release evidence.
