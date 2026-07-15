# Architecture

## Overview

OpenDash is a tablet-first Android smart display and voice terminal.
Three UI modes: Chat (conversation), Dashboard (device grid), Ambient (clock + sensors).

## Module Structure

Single `app` module. Package structure under `com.opendash.app`:

```
assistant/
  model/              AssistantMessage, AssistantSession, ConversationState
  provider/           AssistantProvider interface + implementations
  embedded/         EmbeddedLlmProvider (MediaPipe GenAI on-device)
  openai/           OpenAiCompatibleProvider (REST + SSE streaming)
  anthropic/        AnthropicProvider (Messages API + SSE streaming)
  openclaw/         OpenClawProvider (WebSocket)
  hermes/           HermesAgentProvider (HTTP NDJSON)
  router/             ConversationRouter + RoutingPolicy enum
  session/            ConversationHistoryManager, SessionManager

device/
  model/              Device, DeviceState, DeviceCommand, DeviceCapability, Room
  provider/           DeviceProvider interface + implementations
    homeassistant/    HomeAssistantDeviceProvider
    matter/           MatterDeviceProvider
    mqtt/             MqttDeviceProvider + MqttClientWrapper
    switchbot/        SwitchBotDeviceProvider + SwitchBotApiClient
  tool/               DeviceToolExecutor (LLM function calling → device ops)

voice/
  pipeline/           VoicePipelineState
  stt/                SpeechToText + Android/Whisper delegates
  tts/                TextToSpeech + Android/cloud/Piper delegates
  vad/                amplitude and Silero endpointing
  wakeword/           Vosk default + opt-in openWakeWord

tool/                 ToolCall, ToolResult, ToolSchema, ToolExecutor interface
homeassistant/        HA-specific: client, cache, model, ToolExecutorImpl
ui/                   Compose screens per feature
service/              VoiceService (foreground service), VoiceServiceNotification
data/                 Room (AppDatabase, DAOs, Entities), SecurePreferences
di/                   Hilt modules (AssistantModule, DatabaseModule, NetworkModule, etc.)
```

## Key Abstractions

### AssistantProvider
Defines the contract for AI conversation backends. Each implementation handles
session lifecycle, message sending, streaming, availability, and latency
independently.

```
interface AssistantProvider {
    val id: String
    val displayName: String
    val capabilities: ProviderCapabilities
    suspend fun startSession(config: Map<String, String>): AssistantSession
    suspend fun endSession(session: AssistantSession)
    suspend fun send(session: AssistantSession, messages: List<AssistantMessage>, tools: List<ToolSchema>): AssistantMessage
    fun sendStreaming(session: AssistantSession, messages: List<AssistantMessage>, tools: List<ToolSchema>): Flow<AssistantMessage.Delta>
    suspend fun isAvailable(): Boolean
    suspend fun latencyMs(): Long
}
```

### ConversationRouter
Selects which AssistantProvider to use based on RoutingPolicy:
- **Manual** — user-selected provider
- **Auto** — picks best available
- **Failover** — falls back on error
- **LowestLatency** — benchmarks and picks fastest

### DeviceProvider
Abstraction for smart home device backends. Each protocol (SwitchBot, Matter,
MQTT, HA) implements this interface.

### ToolExecutor
Bridges LLM function calling with device operations. The LLM emits ToolCall
objects; ToolExecutor maps them to DeviceCommand executions and returns ToolResult.

### VoicePipeline
Orchestrates the full voice interaction loop. It pauses/re-arms the hotword
detector around an utterance, runs STT and VAD, checks deterministic fast paths
before the LLM, executes tools when needed, and routes the final answer to TTS.
The ambient surface is primarily wake-word driven, so recovery is part of this
abstraction rather than a UI-only concern.

## Data Flow

```
Microphone → Vosk/openWakeWord → VAD + STT delegate
  → FastPathRouter or ConversationRouter → AssistantProvider
  → ToolExecutor / DeviceManager (if needed) → TTS delegate → Speaker
```

## Dependency Injection

Hilt modules in `di/` package:
- AssistantModule — providers, router
- DatabaseModule — Room, DAOs
- NetworkModule — OkHttpClient
- DeviceModule — device providers, DeviceManager
- VoiceModule — STT, TTS, wake word
- HomeAssistantModule — HA client, cache

## Persistence

- **Room** — conversation history (Sessions + Messages), personal knowledge
  entries (`knowledge` table), routines, memories, and other local state
- **SecurePreferences** — API tokens, secrets (AES256-GCM encrypted)
- **DataStore** — app settings (non-sensitive)
