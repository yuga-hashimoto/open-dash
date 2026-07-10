# Provider Mode Selection (Local LLM vs API) — Design

**Date:** 2026-07-10
**Status:** Approved for planning
**Priority:** Advances Priority 4 (Hybrid gateway) and Priority 3 (UX polish — first-run choice) per `docs/roadmap.md`.

## Problem

OpenDash currently forces every user through on-device model download on first
launch (`MainActivity.kt` gates on `ModelDownloadState.Ready` before anything
else appears). The embedded LLM is small and noticeably weaker than hosted
frontier models. The user wants to choose, at first run and at any time from
Settings, whether OpenDash runs on the embedded local model or against a
cloud API provider — with a provider catalog modeled on OpenCode's `/connect`
experience (but scoped to what's actually feasible in a native Android app;
see "Explicitly out of scope" below).

## Scope decisions (confirmed with user)

1. **Provider coverage**: generic OpenAI-compatible provider (covers the vast
   majority of OpenCode's 75+ providers, since most speak
   `/v1/chat/completions`) + a preset catalog for popular ones + a native
   Anthropic provider (different request/response shape) + a "custom"
   OpenAI-compatible entry for anything else.
2. **Model discovery**: after API key entry, call `GET {baseUrl}/v1/models`
   and populate a picker; fall back to free-text entry when the endpoint
   isn't implemented (e.g. some gateways) or the call fails.
3. **Auth**: three tiers overall — (a) API key entry, (b) device-code OAuth
   (GitHub Copilot as the flagship), (c) user-registered PKCE OAuth (bring
   your own `client_id`/endpoints). **This spec's implementation plan ships
   (a) only** — (b) and (c) are follow-up PRs; see §6.

## Explicitly out of scope

- Replicating OpenCode's "ChatGPT Plus/Pro" / "Claude Pro/Max" subscription
  login. Those use OpenCode's own private OAuth client registrations with
  OpenAI/Anthropic tied to OpenCode-controlled redirect URIs. There is no
  legitimate way to reuse them from a different app.
- GitHub Copilot device-code OAuth ships in this design's first
  implementation phase only as an interface/seam; the actual flow (device
  code exchange + the ~30-minute Copilot session-token refresh loop + the
  editor-impersonation headers Copilot's chat endpoint expects) is
  meaningfully more work than a plain device-code flow and carries its own
  ToS ambiguity. It's a separate follow-up PR, not part of this spec's
  implementation plan.
- Full parity with all 75+ OpenCode providers' bespoke config knobs (AWS
  Bedrock IAM chains, Azure resource-name env vars, SAP AI Core service
  keys, etc). Anything not reachable via "OpenAI-compatible base URL + API
  key" or the Anthropic native format is out of scope; users can still add
  it manually via the "custom" OpenAI-compatible preset if it exposes a
  compatible endpoint.

## Architecture

### 1. Mode gate

New preference `PreferenceKeys.ASSISTANT_MODE` (`stringPreferencesKey`,
values `"local"` / `"api"`, unset = `null`).

`MainActivity.kt` first-run gate changes from:

```
if downloadState != Ready: ModelSetupScreen
else: if needsOnboarding: OnboardingScreen else ModeScaffold
```

to:

```
if ASSISTANT_MODE == null: ProviderModeScreen (pick Local vs API)
else if ASSISTANT_MODE == "local":
    if downloadState != Ready: ModelSetupScreen
    else: providerManager.initialize() once -> OnboardingScreen -> ModeScaffold
else (ASSISTANT_MODE == "api"):
    providerManager.initialize() once -> OnboardingScreen -> ModeScaffold
    // ModelSetupScreen and modelDownloader.fetchAvailableModels() are
    // skipped entirely — no network/model I/O for a mode that doesn't need it
```

`providerManager.initialize()` moves out from inside the `Ready`-only branch
so it fires once the mode is resolved, regardless of which branch is taken.
`ProviderManager.registerEmbeddedLlm()` becomes conditional on
`ASSISTANT_MODE == "local"` so API-mode users never pay the model warm-up
cost.

Settings gets a new "Mode" card at the top of `ProvidersScreen`:
- **API → Local**: if no model downloaded yet, confirm then route to
  `ModelSetupScreen`.
- **Local → API**: if zero `ApiProviderConfig` rows exist, prompt to add one
  first (can't switch into a mode with nothing configured).

### 2. Provider selection persistence (bug fix, prerequisite)

`PreferenceKeys.ACTIVE_PROVIDER_ID` and `ROUTING_POLICY` are declared today
but never read or written anywhere in the codebase — `RoutingPolicy` always
resets to `Auto` on restart. This is latent already, but multi-provider API
mode makes it visible immediately (pick OpenAI, restart, silently get Groq).

Fix, as a prerequisite change:
- `ConversationRouterImpl.selectProvider(id)` persists `id` to
  `ACTIVE_PROVIDER_ID` via `AppPreferences`.
- `ProviderManager.initialize()`, after all providers are registered, reads
  `ACTIVE_PROVIDER_ID` and calls `router.selectProvider(id)` if that provider
  is still registered; otherwise leaves policy at `Auto`.

### 3. Provider config storage

**Decision: DataStore + Moshi JSON, not Room.** `AppDatabase` uses
`fallbackToDestructiveMigration()` (`DatabaseModule.kt:34`) — adding an
entity bumps the schema version and wipes conversation history, memory,
routines, and RAG documents on upgrade. A handful of small provider-config
objects don't need relational queries, so there's no reason to accept that
blast radius. Store them the same way as other structured-but-small settings
in this codebase (catalog-style, mirroring `WhisperModelCatalog`'s
config/catalog split):

```kotlin
@JsonClass(generateAdapter = true)
data class ApiProviderConfig(
    val id: String,            // uuid
    val presetId: String,      // "openai" | "anthropic" | "groq" | ... | "custom"
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val authStyle: String,     // "bearer" | "anthropic" | "none"
    val createdAt: Long
)
```

Stored as a JSON array under a new `stringPreferencesKey("api_provider_configs")`
in `AppPreferences`/DataStore. A small `ApiProviderConfigStore` wraps
read/write/add/remove with Moshi (list adapter), following the existing
`AppPreferences` observe/set pattern.

API keys stay in `SecurePreferences`, per-config, via a new helper
(no more fixed `KEY_*` constants for this one case, since the set of configs
is dynamic):

```kotlin
fun SecurePreferences.apiKeyFor(configId: String) = "api_provider_key_$configId"
```

`ApiProviderConfigStore.remove(id)` also calls
`securePreferences.remove(apiKeyFor(id))` so deleting a provider doesn't
leave an orphaned credential behind.

**Migration of the existing single-provider setting**: on first read after
this ships, if `LOCAL_LLM_BASE_URL` is non-blank and no `ApiProviderConfig`
list exists yet, `ProviderManager` synthesizes one `ApiProviderConfig`
(`presetId = "custom"`) from it, moves the existing
`SecurePreferences.KEY_LOCAL_LLM_API_KEY` value to the new per-config key,
and the old Settings "Local LLM" fields are removed from the UI (superseded
by the new Providers screen).

### 4. Provider catalog & implementations

`ApiProviderCatalog` (plain object, no I/O): preset list — OpenAI, Groq,
OpenRouter, DeepSeek, Together AI, Mistral, Cerebras, Fireworks, Moonshot
(Kimi), Z.AI (GLM), NVIDIA NIM, xAI, Ollama (local), LM Studio (local),
Custom. Each: `id, displayName, defaultBaseUrl, requiresApiKey`.

- All OpenAI-compatible presets reuse the existing `OpenAiCompatibleProvider`
  — changed to take `id: String` and `displayName: String` constructor
  params (currently hardcoded to `"openai_compatible"` / `"Local LLM"`) so
  multiple instances can be registered simultaneously with distinct router
  ids.
- **New `AnthropicProvider`** implements `AssistantProvider` for the one
  preset that isn't OpenAI-shaped: `/v1/messages`, `x-api-key` +
  `anthropic-version` headers instead of `Authorization: Bearer`, and
  `content_block_delta` SSE event shape instead of OpenAI's `delta.content`.
  Mirrors `OpenAiCompatibleProvider`/`OpenAiStreamParser`'s structure with an
  `AnthropicStreamParser`.
- `ProviderManager.initialize()` iterates `ApiProviderConfigStore.list()` and
  constructs the right provider type per `authStyle`, registering each with
  the router, when `ASSISTANT_MODE == "api"`.

### 5. Model discovery

After base URL + API key are entered in the add-provider flow, call
`GET {baseUrl}/v1/models` (reuses `OkHttpClient` from `NetworkModule`).
Success → parse `data[].id` into a picker. Failure (404, non-JSON, timeout)
→ fall back to a free-text model-id field, matching how `llama.cpp`/Ollama/
LM Studio presets already need manual model id entry today.

### 6. OAuth (tiers 2 and 3) — both deferred to follow-up PRs

This spec's implementation plan ships **Tier 1 (API key entry) only**. Tiers
2 and 3 are designed here so the data model doesn't need to change later, but
neither ships code in this phase — the mode gate, storage migration,
catalog, Anthropic provider, model discovery, two new screens, network
security config, and 45-locale i18n pass are already a full plan on their
own; bundling OAuth UI on top risks a PR too large to review safely.

- **Tier 2 (device-code OAuth, GitHub Copilot)**: **deferred**, per
  "Explicitly out of scope" above (Copilot's session-token refresh loop and
  editor-impersonation headers are real work, plus ToS ambiguity).
- **Tier 3 (user-registered PKCE OAuth)**: **deferred**. Planned shape for
  the follow-up: a "Custom OAuth provider" entry point where the user
  supplies `client_id`, `authorizationEndpoint`, `tokenEndpoint`, and
  (optionally) `scope`; `androidx.browser` Custom Tabs opens the
  authorization URL with a PKCE `code_challenge`; the manifest declares an
  `intent-filter` for `opendash://oauth-callback` to capture the redirect;
  the resulting `code` is exchanged for tokens via the token endpoint.
  Access/refresh tokens would be stored via `SecurePreferences` keyed by
  config id, same slot as an API key (transparent to `AssistantProvider`
  implementations — they just read whatever's in the credential slot).

`ApiProviderConfig.authStyle` reserves `"oauth_device"` and `"oauth_pkce"` as
future values (alongside the shipped `"bearer"`, `"anthropic"`, `"none"`) so
adding either tier later doesn't require a schema/store migration.

### 7. UI

- New `ProviderModeScreen` — first-run "Local LLM" vs "API" choice, shown
  before `ModelSetupScreen`/`OnboardingScreen`.
- New `ApiProviderSetupScreen` + `AddApiProviderDialog` — preset picker →
  API key entry → base URL (prefilled, editable for self-hosted / local
  presets) → model picker (§5) → save. (OAuth entry points are added to this
  dialog in the Tier 2/3 follow-ups, not here.)
- `ProvidersScreen` gains: a "Mode" card (§1) at the top, and an "Add
  Provider" affordance. Existing row-selection behavior
  (`router.selectProvider`) is unchanged; §2's persistence fix makes it
  survive restarts.

### 8. Network security

`Ollama` / `LM Studio` presets default to `http://<lan-ip>:port`. Android 9+
blocks cleartext HTTP by default and the app currently has no
`network_security_config.xml`. Add one that permits cleartext only for
user-supplied local/custom base URLs (or document that local presets require
the user's server to be reachable via HTTPS/reverse proxy) — this needs an
explicit decision in the implementation plan, not silently left broken.

### 9. i18n

New user-facing strings (estimated 30–40 keys: mode screen, add-provider
dialog, preset names, model-picker states, OAuth screens) must be added to
`values/strings.xml` and all 44 shipped locales (`docs/i18n.md`'s own table
lists 28 but the repo has grown since; the actual count is
`ls app/src/main/res | grep '^values-[a-z]' | wc -l`). This is sized
explicitly as its own task in the implementation plan, not an afterthought.

## Testing

- `ApiProviderConfigStoreTest` — JSON round-trip, add/remove, orphaned key
  cleanup, migration-from-`LOCAL_LLM_BASE_URL` path.
- `ConversationRouterImplTest` — extend for `ACTIVE_PROVIDER_ID` persistence
  and restore-on-init.
- `AnthropicProviderTest` — `MockWebServer`, SSE streaming, tool-use blocks,
  error mapping.
- `OpenAiCompatibleProviderTest` — update for the new `id`/`displayName`
  constructor params; verify multiple instances can coexist in the router.
- `ProviderModeViewModelTest` / `ApiProviderSetupViewModelTest` — Turbine,
  state transitions (preset select → key entry → model fetch success/failure
  → save).

## Rollout

This spec is scoped for a single implementation plan covering §1–5, §7 (mode
gate, storage, catalog, OpenAI-compatible multi-instance, Anthropic native,
model discovery, first-run + Settings UI) and §8–9 (network security config,
i18n). Tier 3 PKCE OAuth (§6) and Tier 2 GitHub Copilot device-code OAuth are
separate follow-up phases, tracked in `docs/roadmap.md` once this lands.
