# Provider Mode Selection (Local LLM vs API) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user choose, at first run and from Settings, whether OpenDash runs on the embedded on-device model or against a cloud API provider (OpenAI-compatible catalog + native Anthropic), with API-key-only auth in this phase.

**Architecture:** A new `ASSISTANT_MODE` preference gates `MainActivity`'s first-run flow before the existing model-download screen. API provider configs are stored as a JSON list in DataStore (not Room, to avoid the app's destructive-migration blast radius) with API keys in `SecurePreferences` keyed per config id. `ProviderManager` registers either the embedded model or the configured API providers (never both cold-start), reusing the existing `OpenAiCompatibleProvider` (made multi-instance-capable) for OpenAI-shaped presets and a new native `AnthropicProvider` for Anthropic's differently-shaped API.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp, Moshi, DataStore Preferences, JUnit 5 + Truth + MockK + MockWebServer + Turbine (existing stack, no new dependencies).

## Global Constraints

- No `!!`; use `?.`, `?:`, or `requireNotNull`.
- `val` over `var`; immutable data classes with `copy()`.
- No new Gradle dependencies (spec requires asking the user first; everything here is buildable with what's already in `build.gradle.kts`).
- Every new user-facing string goes in `values/strings.xml` **and** all 44 `values-*/strings.xml` locale directories in the same task (`LocaleStringsParityTest` enforces this and will fail the build otherwise).
- `AppDatabase` must NOT gain new entities for this feature — provider configs live in DataStore, per the spec's explicit decision to avoid `fallbackToDestructiveMigration()` data loss.
- OAuth (device-code and PKCE) is explicitly out of scope for every task in this plan — API-key auth only.
- Test runner is JUnit 5 (`org.junit.jupiter.api`), assertions via Google Truth (`com.google.common.truth.Truth.assertThat`), mocking via MockK, coroutine tests via `runTest`.

---

### Task 1: Persist active provider selection (prerequisite bug fix)

`PreferenceKeys.ACTIVE_PROVIDER_ID` and `ROUTING_POLICY` are declared but never read or written anywhere — `RoutingPolicy` silently resets to `Auto` on every process restart. This must be fixed before multi-provider API mode ships, or picking a provider will appear to "not stick."

**Files:**
- Modify: `app/src/main/java/com/opendash/app/assistant/router/ConversationRouterImpl.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/router/ConversationRouterImplTest.kt`

**Interfaces:**
- Consumes: `com.opendash.app.data.preferences.AppPreferences` (existing `suspend fun <T> set(key: Preferences.Key<T>, value: T)`), `PreferenceKeys.ACTIVE_PROVIDER_ID` (existing `stringPreferencesKey`).
- Produces: `ConversationRouterImpl` constructor now takes `(networkMonitor: NetworkMonitor, appPreferences: AppPreferences)` — later tasks that construct it directly (none do; it's Hilt-bound via `AssistantModule`) don't need changes.

- [ ] **Step 1: Write the failing test**

Add to `ConversationRouterImplTest.kt` (update `setup()` to inject a mocked `AppPreferences`, and add a new test):

```kotlin
package com.opendash.app.assistant.router

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.AssistantSession
import com.opendash.app.assistant.provider.AssistantProvider
import com.opendash.app.assistant.provider.ProviderCapabilities
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.tool.ToolSchema
import io.mockk.coVerify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConversationRouterImplTest {

    private lateinit var router: ConversationRouterImpl
    private lateinit var appPreferences: AppPreferences

    @BeforeEach
    fun setup() {
        val nm = io.mockk.mockk<com.opendash.app.util.NetworkMonitor>()
        io.mockk.every { nm.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
        appPreferences = io.mockk.mockk(relaxed = true)
        router = ConversationRouterImpl(nm, appPreferences)
    }

    @Test
    fun `selectProvider persists active id to preferences`() = runTest {
        val provider = createFakeProvider("chosen", available = true)
        router.registerProvider(provider)

        router.selectProvider("chosen")

        coVerify { appPreferences.set(PreferenceKeys.ACTIVE_PROVIDER_ID, "chosen") }
    }

    // ... (existing tests below are unchanged, keep them as-is)
}
```

(Keep every pre-existing test method in the file exactly as-is — only `setup()` gains the `appPreferences` mock and the `ConversationRouterImpl(nm, appPreferences)` constructor call, and the new test above is appended.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.router.ConversationRouterImplTest"`
Expected: FAIL — compile error, `ConversationRouterImpl` takes 1 arg not 2 (since the production constructor hasn't changed yet), or `set` was never called.

- [ ] **Step 3: Write minimal implementation**

In `ConversationRouterImpl.kt`, add the `appPreferences` constructor param and persist on select:

```kotlin
package com.opendash.app.assistant.router

import com.opendash.app.assistant.provider.AssistantProvider
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.util.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRouterImpl @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val appPreferences: AppPreferences
) : ConversationRouter {

    // ... existing _availableProviders / _activeProvider / _policy fields unchanged ...

    override suspend fun selectProvider(providerId: String) {
        val provider = _availableProviders.value.find { it.id == providerId }
            ?: throw IllegalArgumentException("Provider not found: $providerId")
        _activeProvider.value = provider
        _policy.value = RoutingPolicy.Manual(providerId)
        appPreferences.set(PreferenceKeys.ACTIVE_PROVIDER_ID, providerId)
    }

    // ... rest of the class (registerProvider, unregisterProvider, setPolicy,
    // resolveProvider, resolveAuto, resolveFailover, resolveLowestLatency)
    // unchanged from the current implementation ...
}
```

Only the class header (constructor) and `selectProvider` body change; every other method stays byte-for-byte identical to the current file.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.router.ConversationRouterImplTest"`
Expected: PASS (all existing tests + the new one).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/router/ConversationRouterImpl.kt app/src/test/java/com/opendash/app/assistant/router/ConversationRouterImplTest.kt
git commit -m "fix: persist active provider selection across restarts"
```

---

### Task 2: `ApiProviderConfig` model + `ApiProviderConfigStore` + preference keys

**Files:**
- Modify: `app/src/main/java/com/opendash/app/data/preferences/PreferenceKeys.kt`
- Create: `app/src/main/java/com/opendash/app/assistant/provider/api/ApiProviderConfig.kt`
- Create: `app/src/main/java/com/opendash/app/assistant/provider/api/ApiProviderConfigStore.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/api/ApiProviderConfigStoreTest.kt`

**Interfaces:**
- Produces: `ApiProviderConfig(id, presetId, displayName, baseUrl, modelId, authStyle, createdAt)`; `ApiProviderConfigStore.list(): List<ApiProviderConfig>`, `.add(config: ApiProviderConfig, apiKey: String)`, `.remove(id: String)`, `.apiKeyFor(id: String): String`. `PreferenceKeys.ASSISTANT_MODE: Preferences.Key<String>`, `PreferenceKeys.MODE_LOCAL = "local"`, `PreferenceKeys.MODE_API = "api"`, `PreferenceKeys.API_PROVIDER_CONFIGS: Preferences.Key<String>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider.api

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApiProviderConfigStoreTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private lateinit var appPreferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var store: ApiProviderConfigStore
    private var storedJson: String? = null

    @BeforeEach
    fun setup() {
        appPreferences = mockk()
        securePreferences = mockk(relaxed = true)
        storedJson = null
        every { appPreferences.observe(PreferenceKeys.API_PROVIDER_CONFIGS) } answers {
            MutableStateFlow(storedJson)
        }
        coEvery { appPreferences.set(PreferenceKeys.API_PROVIDER_CONFIGS, any()) } answers {
            storedJson = secondArg()
        }
        store = ApiProviderConfigStore(appPreferences, securePreferences, moshi)
    }

    private fun sampleConfig(id: String = "cfg-1") = ApiProviderConfig(
        id = id,
        presetId = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com",
        modelId = "gpt-5.5",
        authStyle = "bearer",
        createdAt = 1L
    )

    @Test
    fun `list is empty when nothing stored`() = runTest {
        assertThat(store.list()).isEmpty()
    }

    @Test
    fun `add then list round-trips the config`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-test")

        val result = store.list()
        assertThat(result).hasSize(1)
        assertThat(result.first().id).isEqualTo("cfg-1")
        assertThat(result.first().displayName).isEqualTo("OpenAI")
    }

    @Test
    fun `add stores the api key under a per-config secure key`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-test")

        verify { securePreferences.putString("api_provider_key_cfg-1", "sk-test") }
    }

    @Test
    fun `add with blank api key does not touch secure preferences`() = runTest {
        store.add(sampleConfig(), apiKey = "")

        verify(exactly = 0) { securePreferences.putString(any(), any()) }
    }

    @Test
    fun `add twice with same id replaces the existing entry`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-1")
        store.add(sampleConfig().copy(displayName = "OpenAI Renamed"), apiKey = "sk-2")

        val result = store.list()
        assertThat(result).hasSize(1)
        assertThat(result.first().displayName).isEqualTo("OpenAI Renamed")
    }

    @Test
    fun `remove deletes the config and its secure key`() = runTest {
        store.add(sampleConfig(), apiKey = "sk-test")

        store.remove("cfg-1")

        assertThat(store.list()).isEmpty()
        verify { securePreferences.remove("api_provider_key_cfg-1") }
    }

    @Test
    fun `apiKeyFor reads from secure preferences using the same key convention`() {
        every { securePreferences.getString("api_provider_key_cfg-1", "") } returns "sk-stored"

        assertThat(store.apiKeyFor("cfg-1")).isEqualTo("sk-stored")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.api.ApiProviderConfigStoreTest"`
Expected: FAIL — `ApiProviderConfig` and `ApiProviderConfigStore` don't exist yet.

- [ ] **Step 3: Write minimal implementation**

Add to `PreferenceKeys.kt` (near the existing `// Local LLM` / `// Routing` section):

```kotlin
    // Assistant mode: "local" (embedded model) | "api" (cloud provider). Unset = first run.
    val ASSISTANT_MODE = stringPreferencesKey("assistant_mode")

    // JSON array of ApiProviderConfig entries (see assistant.provider.api package).
    val API_PROVIDER_CONFIGS = stringPreferencesKey("api_provider_configs")

    companion object {
        const val MODE_LOCAL = "local"
        const val MODE_API = "api"
    }
```

`PreferenceKeys` is declared as `object PreferenceKeys { ... }` — a `companion object` can't nest inside a Kotlin `object`, so declare the two constants as plain top-level `const val` members of the object instead:

```kotlin
    // Assistant mode: "local" (embedded model) | "api" (cloud provider). Unset = first run.
    val ASSISTANT_MODE = stringPreferencesKey("assistant_mode")
    const val MODE_LOCAL = "local"
    const val MODE_API = "api"

    // JSON array of ApiProviderConfig entries (see assistant.provider.api package).
    val API_PROVIDER_CONFIGS = stringPreferencesKey("api_provider_configs")
```

Create `ApiProviderConfig.kt`:

```kotlin
package com.opendash.app.assistant.provider.api

import com.squareup.moshi.JsonClass

/**
 * Persisted configuration for one user-added API provider. `authStyle`
 * selects which AssistantProvider implementation ProviderManager
 * constructs: "bearer" -> OpenAiCompatibleProvider, "anthropic" ->
 * AnthropicProvider, "none" -> OpenAiCompatibleProvider with no auth
 * header (local servers like Ollama/LM Studio).
 */
@JsonClass(generateAdapter = true)
data class ApiProviderConfig(
    val id: String,
    val presetId: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val authStyle: String,
    val createdAt: Long
)
```

Create `ApiProviderConfigStore.kt`:

```kotlin
package com.opendash.app.assistant.provider.api

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiProviderConfigStore @Inject constructor(
    private val appPreferences: AppPreferences,
    private val securePreferences: SecurePreferences,
    moshi: Moshi
) {
    private val listType = Types.newParameterizedType(List::class.java, ApiProviderConfig::class.java)
    private val adapter = moshi.adapter<List<ApiProviderConfig>>(listType)

    suspend fun list(): List<ApiProviderConfig> {
        val json = appPreferences.observe(PreferenceKeys.API_PROVIDER_CONFIGS).first()
        if (json.isNullOrBlank()) return emptyList()
        return adapter.fromJson(json).orEmpty()
    }

    suspend fun add(config: ApiProviderConfig, apiKey: String) {
        val updated = list().filter { it.id != config.id } + config
        appPreferences.set(PreferenceKeys.API_PROVIDER_CONFIGS, adapter.toJson(updated))
        if (apiKey.isNotBlank()) {
            securePreferences.putString(secureKeyFor(config.id), apiKey)
        }
    }

    suspend fun remove(id: String) {
        val updated = list().filter { it.id != id }
        appPreferences.set(PreferenceKeys.API_PROVIDER_CONFIGS, adapter.toJson(updated))
        securePreferences.remove(secureKeyFor(id))
    }

    fun apiKeyFor(id: String): String = securePreferences.getString(secureKeyFor(id))

    private fun secureKeyFor(configId: String) = "api_provider_key_$configId"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.api.ApiProviderConfigStoreTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/data/preferences/PreferenceKeys.kt app/src/main/java/com/opendash/app/assistant/provider/api/ApiProviderConfig.kt app/src/main/java/com/opendash/app/assistant/provider/api/ApiProviderConfigStore.kt app/src/test/java/com/opendash/app/assistant/provider/api/ApiProviderConfigStoreTest.kt
git commit -m "feat: add ApiProviderConfig model and DataStore-backed store"
```

---

### Task 3: `ApiProviderCatalog` presets

Note: Z.AI/GLM is intentionally excluded from the preset list — its OpenAI-compatible endpoint uses `/api/paas/v4/...` while `OpenAiCompatibleProvider` hardcodes `/v1/chat/completions`, so a Z.AI preset with this provider would silently 404. Users can still reach it via the "custom" preset once they know the right base path; this plan doesn't special-case it.

**Files:**
- Create: `app/src/main/java/com/opendash/app/assistant/provider/api/ApiProviderCatalog.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/api/ApiProviderCatalogTest.kt`

**Interfaces:**
- Produces: `ApiProviderCatalog.Preset(id, displayName, defaultBaseUrl, requiresApiKey, authStyle)`, `ApiProviderCatalog.presets: List<Preset>`, `ApiProviderCatalog.find(id: String): Preset?`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider.api

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ApiProviderCatalogTest {

    @Test
    fun `every preset id is unique`() {
        val ids = ApiProviderCatalog.presets.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `find returns the matching preset`() {
        val found = ApiProviderCatalog.find("openai")
        assertThat(found).isNotNull()
        assertThat(found!!.displayName).isEqualTo("OpenAI")
        assertThat(found.authStyle).isEqualTo("bearer")
    }

    @Test
    fun `find returns null for unknown id`() {
        assertThat(ApiProviderCatalog.find("does-not-exist")).isNull()
    }

    @Test
    fun `anthropic preset uses anthropic auth style`() {
        val found = ApiProviderCatalog.find("anthropic")
        assertThat(found).isNotNull()
        assertThat(found!!.authStyle).isEqualTo("anthropic")
    }

    @Test
    fun `local presets do not require an api key`() {
        assertThat(ApiProviderCatalog.find("ollama")!!.requiresApiKey).isFalse()
        assertThat(ApiProviderCatalog.find("lmstudio")!!.requiresApiKey).isFalse()
    }

    @Test
    fun `custom preset has a blank default base url for manual entry`() {
        assertThat(ApiProviderCatalog.find("custom")!!.defaultBaseUrl).isEmpty()
    }

    @Test
    fun `catalog does not include zai due to non-v1 endpoint path`() {
        assertThat(ApiProviderCatalog.find("zai")).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.api.ApiProviderCatalogTest"`
Expected: FAIL — `ApiProviderCatalog` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.opendash.app.assistant.provider.api

/**
 * Static catalog of OpenAI-compatible / Anthropic-native presets shown in
 * the "Add Provider" flow. Everything here except "anthropic" is
 * registered as an OpenAiCompatibleProvider instance by ProviderManager;
 * "anthropic" is registered as a native AnthropicProvider (see §4 of the
 * design spec — /v1/messages request/response shape differs from OpenAI).
 */
object ApiProviderCatalog {

    data class Preset(
        val id: String,
        val displayName: String,
        val defaultBaseUrl: String,
        val requiresApiKey: Boolean,
        val authStyle: String
    )

    val presets: List<Preset> = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com", true, "bearer"),
        Preset("anthropic", "Anthropic", "https://api.anthropic.com", true, "anthropic"),
        Preset("groq", "Groq", "https://api.groq.com/openai", true, "bearer"),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api", true, "bearer"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com", true, "bearer"),
        Preset("together", "Together AI", "https://api.together.xyz", true, "bearer"),
        Preset("mistral", "Mistral", "https://api.mistral.ai", true, "bearer"),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai", true, "bearer"),
        Preset("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference", true, "bearer"),
        Preset("moonshot", "Moonshot AI (Kimi)", "https://api.moonshot.ai", true, "bearer"),
        Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com", true, "bearer"),
        Preset("xai", "xAI (Grok)", "https://api.x.ai", true, "bearer"),
        Preset("ollama", "Ollama (local)", "http://localhost:11434", false, "none"),
        Preset("lmstudio", "LM Studio (local)", "http://localhost:1234", false, "none"),
        Preset("custom", "Custom", "", false, "bearer")
    )

    fun find(id: String): Preset? = presets.find { it.id == id }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.api.ApiProviderCatalogTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/provider/api/ApiProviderCatalog.kt app/src/test/java/com/opendash/app/assistant/provider/api/ApiProviderCatalogTest.kt
git commit -m "feat: add ApiProviderCatalog preset list"
```

---

### Task 4: `OpenAiCompatibleProvider` multi-instance support (id/displayName ctor params)

Today `id`/`displayName` are hardcoded to `"openai_compatible"`/`"Local LLM"`, so only one instance can be meaningfully registered with the router at a time. Making them constructor params (with those same values as defaults, so the existing embedded-mode call site keeps working unchanged) lets `ProviderManager` register N configured API providers simultaneously with distinct router ids.

**Files:**
- Modify: `app/src/main/java/com/opendash/app/assistant/provider/openai/OpenAiCompatibleProvider.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/openai/OpenAiCompatibleProviderTest.kt` (new — none exists today)

**Interfaces:**
- Produces: `OpenAiCompatibleProvider(client, moshi, config, id: String = "openai_compatible", displayName: String = "Local LLM")`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider.openai

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `defaults preserve the embedded-mode id and display name`() {
        val provider = OpenAiCompatibleProvider(
            client = client,
            moshi = moshi,
            config = OpenAiCompatibleConfig(baseUrl = "http://example")
        )
        assertThat(provider.id).isEqualTo("openai_compatible")
        assertThat(provider.displayName).isEqualTo("Local LLM")
    }

    @Test
    fun `custom id and display name are used when supplied`() {
        val provider = OpenAiCompatibleProvider(
            client = client,
            moshi = moshi,
            config = OpenAiCompatibleConfig(baseUrl = "http://example"),
            id = "api_abc123",
            displayName = "My OpenAI Account"
        )
        assertThat(provider.id).isEqualTo("api_abc123")
        assertThat(provider.displayName).isEqualTo("My OpenAI Account")
    }

    @Test
    fun `two instances with different ids can both call their own base url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"hi from A"}}]}"""))
        val providerA = OpenAiCompatibleProvider(
            client = client,
            moshi = moshi,
            config = OpenAiCompatibleConfig(baseUrl = server.url("/").toString().trimEnd('/')),
            id = "api_a",
            displayName = "Provider A"
        )
        val session = providerA.startSession()
        val msg = providerA.send(session, listOf(AssistantMessage.User(content = "hi")), emptyList())
        assertThat((msg as AssistantMessage.Assistant).content).isEqualTo("hi from A")
        assertThat(providerA.id).isEqualTo("api_a")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.openai.OpenAiCompatibleProviderTest"`
Expected: FAIL — constructor doesn't accept `id`/`displayName` params yet.

- [ ] **Step 3: Write minimal implementation**

In `OpenAiCompatibleProvider.kt`, change the class header only (body is unchanged):

```kotlin
class OpenAiCompatibleProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val config: OpenAiCompatibleConfig,
    override val id: String = "openai_compatible",
    override val displayName: String = "Local LLM"
) : AssistantProvider {

    override val capabilities = ProviderCapabilities(
        supportsStreaming = true,
        supportsTools = true,
        maxContextTokens = config.maxTokens,
        modelName = config.model
    )

    // ... everything below `capabilities` (parser, jsonMediaType, startSession,
    // endSession, send, sendStreaming, isAvailable, latencyMs, buildHttpRequest,
    // buildRequestBody) stays exactly as it is today — no other lines change.
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.openai.OpenAiCompatibleProviderTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/provider/openai/OpenAiCompatibleProvider.kt app/src/test/java/com/opendash/app/assistant/provider/openai/OpenAiCompatibleProviderTest.kt
git commit -m "feat: make OpenAiCompatibleProvider id/displayName configurable for multi-instance use"
```

---

### Task 5: `AnthropicStreamParser`

Anthropic's Messages API streams SSE `data:` lines whose JSON payload carries its own `"type"` field (`content_block_start`, `content_block_delta`, `message_delta`, ...) — unlike OpenAI's stream, there's no separate SSE `event:` line to key off. Tool-call argument fragments (`input_json_delta`) are emitted as `ToolCallRequest` continuation fragments (empty `id`/`name`, partial-JSON `arguments`) that the existing `StreamingToolCallAggregator` (`assistant/agent/StreamingToolCallAggregator.kt`) already knows how to glue back together — no new aggregation logic needed here.

**Files:**
- Create: `app/src/main/java/com/opendash/app/assistant/provider/anthropic/AnthropicStreamParser.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/anthropic/AnthropicStreamParserTest.kt`

**Interfaces:**
- Consumes: `com.opendash.app.assistant.model.AssistantMessage`, `com.opendash.app.assistant.model.ToolCallRequest` (existing).
- Produces: `AnthropicStreamParser(moshi: Moshi)` with `fun parseLine(line: String): AssistantMessage.Delta?` and `fun parseFullResponse(json: String): AssistantMessage`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider.anthropic

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.jupiter.api.Test

class AnthropicStreamParserTest {

    private val parser = AnthropicStreamParser(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())

    @Test
    fun `parseLine extracts text_delta content`() {
        val line = """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.contentDelta).isEqualTo("Hello")
    }

    @Test
    fun `parseLine returns null for non-data lines`() {
        assertThat(parser.parseLine("event: content_block_delta")).isNull()
        assertThat(parser.parseLine("")).isNull()
    }

    @Test
    fun `parseLine extracts finish reason from message_delta`() {
        val line = """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.finishReason).isEqualTo("end_turn")
    }

    @Test
    fun `parseLine emits a tool call header on content_block_start with tool_use`() {
        val line = """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.toolCallDelta).isNotNull()
        assertThat(delta.toolCallDelta!!.id).isEqualTo("toolu_1")
        assertThat(delta.toolCallDelta.name).isEqualTo("get_weather")
    }

    @Test
    fun `parseLine emits a tool call argument fragment on input_json_delta`() {
        val line = """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"location\":"}}"""
        val delta = parser.parseLine(line)
        assertThat(delta).isNotNull()
        assertThat(delta!!.toolCallDelta).isNotNull()
        assertThat(delta.toolCallDelta!!.id).isEmpty()
        assertThat(delta.toolCallDelta.arguments).isEqualTo("{\"location\":")
    }

    @Test
    fun `parseFullResponse concatenates text blocks`() {
        val json = """{"content":[{"type":"text","text":"Hi "},{"type":"text","text":"there"}],"stop_reason":"end_turn"}"""
        val msg = parser.parseFullResponse(json) as AssistantMessage.Assistant
        assertThat(msg.content).isEqualTo("Hi there")
        assertThat(msg.toolCalls).isEmpty()
    }

    @Test
    fun `parseFullResponse extracts tool_use blocks as tool calls`() {
        val json = """{"content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"location":"Tokyo"}}],"stop_reason":"tool_use"}"""
        val msg = parser.parseFullResponse(json) as AssistantMessage.Assistant
        assertThat(msg.toolCalls).hasSize(1)
        assertThat(msg.toolCalls.first().id).isEqualTo("toolu_1")
        assertThat(msg.toolCalls.first().name).isEqualTo("get_weather")
        assertThat(msg.toolCalls.first().arguments).contains("Tokyo")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.anthropic.AnthropicStreamParserTest"`
Expected: FAIL — `AnthropicStreamParser` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.opendash.app.assistant.provider.anthropic

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.ToolCallRequest
import com.squareup.moshi.Moshi
import timber.log.Timber

private typealias JsonMap = Map<String, Any?>

/**
 * Parses Anthropic Messages API SSE lines and full (non-streaming)
 * responses. Unlike OpenAI's stream, every Anthropic event carries its
 * own "type" field inside the JSON payload, so there's no need to track
 * a separate SSE "event:" line.
 */
@Suppress("UNCHECKED_CAST")
class AnthropicStreamParser(private val moshi: Moshi) {

    fun parseLine(line: String): AssistantMessage.Delta? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data: ")) return null
        val json = trimmed.removePrefix("data: ")

        return try {
            val map = moshi.adapter(Map::class.java).fromJson(json) as? JsonMap ?: return null
            when (map["type"] as? String) {
                "content_block_delta" -> parseContentBlockDelta(map)
                "content_block_start" -> parseContentBlockStart(map)
                "message_delta" -> parseMessageDelta(map)
                else -> null
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Anthropic SSE line: $json")
            null
        }
    }

    private fun parseContentBlockDelta(map: JsonMap): AssistantMessage.Delta? {
        val delta = map["delta"] as? JsonMap ?: return null
        return when (delta["type"] as? String) {
            "text_delta" -> AssistantMessage.Delta(contentDelta = delta["text"] as? String ?: "")
            "input_json_delta" -> AssistantMessage.Delta(
                toolCallDelta = ToolCallRequest(id = "", name = "", arguments = delta["partial_json"] as? String ?: "")
            )
            else -> null
        }
    }

    private fun parseContentBlockStart(map: JsonMap): AssistantMessage.Delta? {
        val block = map["content_block"] as? JsonMap ?: return null
        if (block["type"] as? String != "tool_use") return null
        return AssistantMessage.Delta(
            toolCallDelta = ToolCallRequest(
                id = block["id"] as? String ?: "",
                name = block["name"] as? String ?: "",
                arguments = ""
            )
        )
    }

    private fun parseMessageDelta(map: JsonMap): AssistantMessage.Delta? {
        val delta = map["delta"] as? JsonMap ?: return null
        val stopReason = delta["stop_reason"] as? String ?: return null
        return AssistantMessage.Delta(finishReason = stopReason)
    }

    fun parseFullResponse(json: String): AssistantMessage {
        return try {
            val map = moshi.adapter(Map::class.java).fromJson(json) as? JsonMap
                ?: return AssistantMessage.Assistant(content = "")
            val blocks = map["content"] as? List<*> ?: return AssistantMessage.Assistant(content = "")

            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCallRequest>()
            blocks.forEach { block ->
                val b = block as? JsonMap ?: return@forEach
                when (b["type"] as? String) {
                    "text" -> text.append(b["text"] as? String ?: "")
                    "tool_use" -> toolCalls.add(
                        ToolCallRequest(
                            id = b["id"] as? String ?: "",
                            name = b["name"] as? String ?: "",
                            arguments = moshi.adapter(Map::class.java).toJson(b["input"] as? JsonMap ?: emptyMap<String, Any?>())
                        )
                    )
                }
            }
            AssistantMessage.Assistant(content = text.toString(), toolCalls = toolCalls)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Anthropic full response")
            AssistantMessage.Assistant(content = "")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.anthropic.AnthropicStreamParserTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/provider/anthropic/AnthropicStreamParser.kt app/src/test/java/com/opendash/app/assistant/provider/anthropic/AnthropicStreamParserTest.kt
git commit -m "feat: add AnthropicStreamParser for the Messages API SSE shape"
```

---

### Task 6: `AnthropicConfig` + `AnthropicProvider`

**Files:**
- Create: `app/src/main/java/com/opendash/app/assistant/provider/anthropic/AnthropicConfig.kt`
- Create: `app/src/main/java/com/opendash/app/assistant/provider/anthropic/AnthropicProvider.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/anthropic/AnthropicProviderTest.kt`

**Interfaces:**
- Consumes: `AnthropicStreamParser` (Task 5), `com.opendash.app.tool.ToolSchema`/`ToolParameter` (existing).
- Produces: `AnthropicConfig(baseUrl, apiKey, model, maxTokens, systemPrompt, anthropicVersion)`; `AnthropicProvider(client, moshi, config, id = "anthropic", displayName = "Anthropic") : AssistantProvider`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider.anthropic

import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.model.AssistantMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class AnthropicProviderTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun provider(apiKey: String = "sk-ant-test") = AnthropicProvider(
        client = client,
        moshi = moshi,
        config = AnthropicConfig(baseUrl = server.url("/").toString().trimEnd('/'), apiKey = apiKey, model = "claude-sonnet-5")
    )

    @Test
    fun `capabilities declare a non-local streaming tool-capable provider`() {
        assertThat(provider().capabilities.isLocal).isFalse()
        assertThat(provider().capabilities.supportsStreaming).isTrue()
        assertThat(provider().capabilities.supportsTools).isTrue()
    }

    @Test
    fun `send parses a non-streaming text response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"content":[{"type":"text","text":"Hello there"}],"stop_reason":"end_turn"}"""
            )
        )
        val p = provider()
        val session = p.startSession()
        val msg = p.send(session, listOf(AssistantMessage.User(content = "hi")), emptyList())
        assertThat((msg as AssistantMessage.Assistant).content).isEqualTo("Hello there")
    }

    @Test
    fun `send sets x-api-key and anthropic-version headers, not Authorization`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider(apiKey = "sk-ant-secret")
        p.send(p.startSession(), listOf(AssistantMessage.User(content = "hi")), emptyList())

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("sk-ant-secret")
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01")
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    fun `send posts to the v1 messages endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val p = provider()
        p.send(p.startSession(), listOf(AssistantMessage.User(content = "hi")), emptyList())

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/messages")
    }

    @Test
    fun `isAvailable returns true on 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertThat(provider().isAvailable()).isTrue()
    }

    @Test
    fun `isAvailable returns false on error status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(provider().isAvailable()).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.anthropic.AnthropicProviderTest"`
Expected: FAIL — `AnthropicConfig`/`AnthropicProvider` don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.opendash.app.assistant.provider.anthropic

data class AnthropicConfig(
    val baseUrl: String = "https://api.anthropic.com",
    val apiKey: String = "",
    val model: String = "claude-sonnet-5",
    val maxTokens: Int = 4096,
    val systemPrompt: String = "",
    val anthropicVersion: String = "2023-06-01"
)
```

```kotlin
package com.opendash.app.assistant.provider.anthropic

import com.opendash.app.assistant.model.AssistantMessage
import com.opendash.app.assistant.model.AssistantSession
import com.opendash.app.assistant.provider.AssistantProvider
import com.opendash.app.assistant.provider.ProviderCapabilities
import com.opendash.app.tool.ToolSchema
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AnthropicProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val config: AnthropicConfig,
    override val id: String = "anthropic",
    override val displayName: String = "Anthropic"
) : AssistantProvider {

    override val capabilities = ProviderCapabilities(
        supportsStreaming = true,
        supportsTools = true,
        maxContextTokens = 200_000,
        modelName = config.model
    )

    private val parser = AnthropicStreamParser(moshi)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun startSession(config: Map<String, String>): AssistantSession =
        AssistantSession(providerId = id)

    override suspend fun endSession(session: AssistantSession) {
        // Stateless REST - nothing to clean up
    }

    override suspend fun send(
        session: AssistantSession,
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>
    ): AssistantMessage = suspendCancellableCoroutine { cont ->
        val body = buildRequestBody(messages, tools, stream = false)
        val request = buildHttpRequest(body)

        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        try {
            val response = call.execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                cont.resumeWithException(RuntimeException("HTTP ${response.code}: $responseBody"))
                return@suspendCancellableCoroutine
            }
            cont.resume(parser.parseFullResponse(responseBody))
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    override fun sendStreaming(
        session: AssistantSession,
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>
    ): Flow<AssistantMessage.Delta> = flow {
        val body = buildRequestBody(messages, tools, stream = true)
        val request = buildHttpRequest(body)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: ${response.body?.string()}")
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
        reader.use {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parsed = parser.parseLine(line!!) ?: continue
                emit(parsed)
                if (parsed.finishReason != null) break
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("${config.baseUrl}/v1/models")
                .addHeader("x-api-key", config.apiKey)
                .addHeader("anthropic-version", config.anthropicVersion)
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            Timber.d(e, "Anthropic provider unavailable: ${config.baseUrl}")
            false
        }
    }

    override suspend fun latencyMs(): Long {
        val start = java.lang.System.currentTimeMillis()
        isAvailable()
        return java.lang.System.currentTimeMillis() - start
    }

    private fun buildHttpRequest(body: String): Request {
        return Request.Builder()
            .url("${config.baseUrl}/v1/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", config.anthropicVersion)
            .post(body.toRequestBody(jsonMediaType))
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildRequestBody(
        messages: List<AssistantMessage>,
        tools: List<ToolSchema>,
        stream: Boolean
    ): String {
        val msgList = messages.mapNotNull { msg ->
            when (msg) {
                is AssistantMessage.User -> mapOf("role" to "user", "content" to msg.content)
                is AssistantMessage.Assistant -> mapOf("role" to "assistant", "content" to msg.content)
                is AssistantMessage.ToolCallResult -> mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf(
                            "type" to "tool_result",
                            "tool_use_id" to msg.callId,
                            "content" to msg.result,
                            "is_error" to msg.isError
                        )
                    )
                )
                is AssistantMessage.System -> null
                is AssistantMessage.Delta -> null
            }
        }

        val systemText = messages.filterIsInstance<AssistantMessage.System>()
            .joinToString("\n") { it.content }
            .ifBlank { config.systemPrompt }

        val payload = mutableMapOf<String, Any>(
            "model" to config.model,
            "max_tokens" to config.maxTokens,
            "messages" to msgList,
            "stream" to stream
        )
        if (systemText.isNotBlank()) payload["system"] = systemText

        if (tools.isNotEmpty()) {
            payload["tools"] = tools.map { tool ->
                mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "input_schema" to mapOf(
                        "type" to "object",
                        "properties" to tool.parameters.mapValues { (_, param) ->
                            mutableMapOf<String, Any>(
                                "type" to param.type,
                                "description" to param.description
                            ).apply {
                                param.enum?.let { put("enum", it) }
                            }
                        },
                        "required" to tool.parameters.filter { it.value.required }.keys.toList()
                    )
                )
            }
        }

        return moshi.adapter(Map::class.java).toJson(payload as Map<Any?, Any?>) ?: "{}"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.anthropic.AnthropicProviderTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/provider/anthropic/AnthropicConfig.kt app/src/main/java/com/opendash/app/assistant/provider/anthropic/AnthropicProvider.kt app/src/test/java/com/opendash/app/assistant/provider/anthropic/AnthropicProviderTest.kt
git commit -m "feat: add native AnthropicProvider for the Messages API"
```

---

### Task 7: `ModelListFetcher` (GET /v1/models)

**Files:**
- Create: `app/src/main/java/com/opendash/app/assistant/provider/api/ModelListFetcher.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/api/ModelListFetcherTest.kt`

**Interfaces:**
- Produces: `ModelListFetcher(client: OkHttpClient, moshi: Moshi)` with `suspend fun fetch(baseUrl: String, apiKey: String, authStyle: String): Result<List<String>>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider.api

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ModelListFetcherTest {

    private lateinit var server: MockWebServer
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private lateinit var fetcher: ModelListFetcher

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        fetcher = ModelListFetcher(client, moshi)
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `fetch parses model ids from the data array`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"gpt-5.5"},{"id":"gpt-5.5-mini"}]}"""
            )
        )
        val result = fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).containsExactly("gpt-5.5", "gpt-5.5-mini")
    }

    @Test
    fun `fetch sends bearer auth header for bearer style`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test")
    }

    @Test
    fun `fetch sends x-api-key and anthropic-version for anthropic style`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-ant-test", "anthropic")
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("sk-ant-test")
        assertThat(recorded.getHeader("anthropic-version")).isNotNull()
    }

    @Test
    fun `fetch returns failure on non-2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `fetch returns failure on unparseable body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val result = fetcher.fetch(server.url("/").toString().trimEnd('/'), "sk-test", "bearer")
        assertThat(result.isFailure).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.api.ModelListFetcherTest"`
Expected: FAIL — `ModelListFetcher` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.opendash.app.assistant.provider.api

import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelListFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi
) {
    suspend fun fetch(baseUrl: String, apiKey: String, authStyle: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder().url("$baseUrl/v1/models")
                when (authStyle) {
                    "anthropic" -> {
                        if (apiKey.isNotBlank()) requestBuilder.addHeader("x-api-key", apiKey)
                        requestBuilder.addHeader("anthropic-version", "2023-06-01")
                    }
                    "bearer" -> if (apiKey.isNotBlank()) {
                        requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                }
                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                @Suppress("UNCHECKED_CAST")
                val map = moshi.adapter(Map::class.java).fromJson(body) as? Map<String, Any?>
                    ?: return@withContext Result.failure(IOException("Unparseable /v1/models response"))
                val data = map["data"] as? List<*>
                    ?: return@withContext Result.failure(IOException("Missing 'data' field"))
                val ids = data.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
                Result.success(ids)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.api.ModelListFetcherTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/provider/api/ModelListFetcher.kt app/src/test/java/com/opendash/app/assistant/provider/api/ModelListFetcherTest.kt
git commit -m "feat: add ModelListFetcher for GET /v1/models discovery"
```

---

### Task 8: `ProviderManager` rewiring — mode-conditional registration, migration, restore

**Files:**
- Modify: `app/src/main/java/com/opendash/app/assistant/provider/ProviderManager.kt`
- Test: `app/src/test/java/com/opendash/app/assistant/provider/ProviderManagerTest.kt` (new — no test exists today)

**Interfaces:**
- Consumes: `ApiProviderConfigStore` (Task 2), `ApiProviderCatalog` (Task 3), `AnthropicProvider`/`AnthropicConfig` (Task 6), `OpenAiCompatibleProvider` (Task 4), `PreferenceKeys.ASSISTANT_MODE`/`MODE_LOCAL`/`MODE_API` (Task 2), `ConversationRouterImpl.selectProvider` (Task 1).
- Produces: `ProviderManager` constructor gains `apiProviderConfigStore: ApiProviderConfigStore` param. `initialize()` behavior: migrates legacy `LOCAL_LLM_BASE_URL` into an `ApiProviderConfig` once, registers embedded LLM only when mode is `local` (or unset), registers all configured API providers when mode is `api`, and restores the persisted active provider selection.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.assistant.provider

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.assistant.skills.SkillRegistry
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.opendash.app.device.DeviceManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderManagerTest {

    private lateinit var context: Context
    private lateinit var router: ConversationRouter
    private lateinit var preferences: AppPreferences
    private lateinit var securePreferences: SecurePreferences
    private lateinit var apiProviderConfigStore: ApiProviderConfigStore
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val client = OkHttpClient()

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        router = mockk(relaxed = true)
        preferences = mockk()
        securePreferences = mockk(relaxed = true)
        apiProviderConfigStore = mockk()

        every { preferences.observe(PreferenceKeys.CUSTOM_SYSTEM_PROMPT) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.LOCAL_LLM_BASE_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.LOCAL_LLM_MODEL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.OPENCLAW_GATEWAY_URL) } returns flowOf(null)
        every { preferences.observe(PreferenceKeys.ASSISTANT_MODE) } returns flowOf(PreferenceKeys.MODE_API)
        every { preferences.observe(PreferenceKeys.ACTIVE_PROVIDER_ID) } returns flowOf(null)
        coEvery { apiProviderConfigStore.list() } returns emptyList()
        every { router.availableProviders } returns MutableStateFlow(emptyList())
    }

    private fun manager() = ProviderManager(
        context = context,
        router = router,
        preferences = preferences,
        securePreferences = securePreferences,
        client = client,
        moshi = moshi,
        skillRegistry = mockk(relaxed = true),
        deviceManager = mockk(relaxed = true),
        apiProviderConfigStore = apiProviderConfigStore
    )

    @Test
    fun `api mode registers configured providers and skips embedded model`() = runTest {
        val config = ApiProviderConfig(
            id = "cfg-1",
            presetId = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com",
            modelId = "gpt-5.5",
            authStyle = "bearer",
            createdAt = 1L
        )
        coEvery { apiProviderConfigStore.list() } returns listOf(config)
        every { apiProviderConfigStore.apiKeyFor("cfg-1") } returns "sk-test"

        manager().initialize()
        advanceUntilIdle()

        coVerify { router.registerProvider(match { it.id == "api_cfg-1" && it.displayName == "OpenAI" }) }
    }

    @Test
    fun `api mode does not attempt to list local models`() = runTest {
        // ModelManager.listAvailableModels() touches the filesystem via the
        // real Context; api mode must never call registerEmbeddedLlm's path,
        // which we verify indirectly: no provider named "openai_compatible"
        // (the embedded-mode default id) is ever registered.
        manager().initialize()
        advanceUntilIdle()

        coVerify(exactly = 0) { router.registerProvider(match { it.id == "openai_compatible" }) }
    }

    @Test
    fun `restores previously active provider id after registering`() = runTest {
        val config = ApiProviderConfig(
            id = "cfg-1", presetId = "openai", displayName = "OpenAI",
            baseUrl = "https://api.openai.com", modelId = "gpt-5.5",
            authStyle = "bearer", createdAt = 1L
        )
        coEvery { apiProviderConfigStore.list() } returns listOf(config)
        every { apiProviderConfigStore.apiKeyFor("cfg-1") } returns "sk-test"
        every { preferences.observe(PreferenceKeys.ACTIVE_PROVIDER_ID) } returns flowOf("api_cfg-1")
        every { router.availableProviders } returns MutableStateFlow(
            listOf(
                OpenAiCompatibleProviderFakeId("api_cfg-1")
            )
        )

        manager().initialize()
        advanceUntilIdle()

        coVerify { router.selectProvider("api_cfg-1") }
    }

    private fun OpenAiCompatibleProviderFakeId(providerId: String): AssistantProvider {
        val fake = mockk<AssistantProvider>(relaxed = true)
        every { fake.id } returns providerId
        return fake
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.ProviderManagerTest"`
Expected: FAIL — `ProviderManager` constructor doesn't take `apiProviderConfigStore` yet, and the mode-conditional logic doesn't exist.

- [ ] **Step 3: Write minimal implementation**

Replace the full contents of `ProviderManager.kt`:

```kotlin
package com.opendash.app.assistant.provider

import android.content.Context
import com.opendash.app.assistant.provider.anthropic.AnthropicConfig
import com.opendash.app.assistant.provider.anthropic.AnthropicProvider
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmConfig
import com.opendash.app.assistant.provider.embedded.EmbeddedLlmProvider
import com.opendash.app.assistant.provider.embedded.HardwareProfile
import com.opendash.app.assistant.provider.embedded.ModelManager
import com.opendash.app.assistant.skills.SkillRegistry
import com.opendash.app.device.DeviceManager
import com.opendash.app.assistant.provider.openai.OpenAiCompatibleConfig
import com.opendash.app.assistant.provider.openai.OpenAiCompatibleProvider
import com.opendash.app.assistant.provider.openclaw.OpenClawConfig
import com.opendash.app.assistant.provider.openclaw.OpenClawProvider
import com.opendash.app.assistant.router.ConversationRouter
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: ConversationRouter,
    private val preferences: AppPreferences,
    private val securePreferences: SecurePreferences,
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val skillRegistry: SkillRegistry,
    private val deviceManager: DeviceManager,
    private val apiProviderConfigStore: ApiProviderConfigStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelManager = ModelManager(context)

    fun initialize() {
        scope.launch {
            migrateLegacyLocalLlmSettingIfNeeded()
            val mode = preferences.observe(PreferenceKeys.ASSISTANT_MODE).first()
            if (mode == PreferenceKeys.MODE_API) {
                registerConfiguredApiProviders()
            } else {
                registerEmbeddedLlm()
            }
            registerOpenClawIfConfigured()
            restoreActiveProviderSelection()
        }
    }

    private suspend fun migrateLegacyLocalLlmSettingIfNeeded() {
        if (apiProviderConfigStore.list().isNotEmpty()) return
        val legacyUrl = preferences.observe(PreferenceKeys.LOCAL_LLM_BASE_URL).first()
        if (legacyUrl.isNullOrBlank()) return
        val legacyModel = preferences.observe(PreferenceKeys.LOCAL_LLM_MODEL).first() ?: "gemma-4-e2b"
        val legacyApiKey = securePreferences.getString(SecurePreferences.KEY_LOCAL_LLM_API_KEY)
        val migrated = ApiProviderConfig(
            id = java.util.UUID.randomUUID().toString(),
            presetId = "custom",
            displayName = "Migrated Local LLM",
            baseUrl = legacyUrl,
            modelId = legacyModel,
            authStyle = "bearer",
            createdAt = java.lang.System.currentTimeMillis()
        )
        apiProviderConfigStore.add(migrated, legacyApiKey)
        preferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API)
        Timber.d("Migrated legacy LOCAL_LLM_BASE_URL into ApiProviderConfig ${migrated.id}")
    }

    private suspend fun registerEmbeddedLlm() {
        try {
            val models = modelManager.listAvailableModels()
            if (models.isNotEmpty()) {
                val modelPath = models.first().path
                val customPrompt = preferences.observe(PreferenceKeys.CUSTOM_SYSTEM_PROMPT).first()
                val systemPrompt = customPrompt?.takeIf { it.isNotBlank() }
                    ?: EmbeddedLlmConfig.DEFAULT_SYSTEM_PROMPT
                val profile = HardwareProfile.fromContext(context)
                val config = EmbeddedLlmConfig.forHardware(
                    modelPath = modelPath,
                    profile = profile,
                    systemPrompt = systemPrompt
                )
                Timber.d("EmbeddedLlm tuned for ${profile.tier}: ctx=${config.contextSize} threads=${config.threads}")
                val provider = EmbeddedLlmProvider(
                    context = context,
                    config = config,
                    skillRegistry = skillRegistry,
                    deviceManager = deviceManager
                )
                router.registerProvider(provider)
                Timber.d("Registered EmbeddedLlmProvider with model: ${models.first().name} (custom prompt: ${!customPrompt.isNullOrBlank()})")
                scope.launch { provider.warmUp() }
            } else {
                Timber.d("No models found, EmbeddedLlmProvider not registered")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to register EmbeddedLlmProvider")
        }
    }

    private suspend fun registerConfiguredApiProviders() {
        apiProviderConfigStore.list().forEach { config ->
            try {
                val apiKey = apiProviderConfigStore.apiKeyFor(config.id)
                val routerId = "api_${config.id}"
                val provider: AssistantProvider = if (config.authStyle == "anthropic") {
                    AnthropicProvider(
                        client = client,
                        moshi = moshi,
                        config = AnthropicConfig(baseUrl = config.baseUrl, apiKey = apiKey, model = config.modelId),
                        id = routerId,
                        displayName = config.displayName
                    )
                } else {
                    OpenAiCompatibleProvider(
                        client = client,
                        moshi = moshi,
                        config = OpenAiCompatibleConfig(baseUrl = config.baseUrl, apiKey = apiKey, model = config.modelId),
                        id = routerId,
                        displayName = config.displayName
                    )
                }
                router.registerProvider(provider)
                Timber.d("Registered API provider: ${config.displayName} ($routerId)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to register API provider ${config.id}")
            }
        }
    }

    private suspend fun registerOpenClawIfConfigured() {
        try {
            val url = preferences.observe(PreferenceKeys.OPENCLAW_GATEWAY_URL).first()
            if (!url.isNullOrBlank()) {
                val provider = OpenClawProvider(
                    client = client,
                    moshi = moshi,
                    config = OpenClawConfig(gatewayUrl = url)
                )
                router.registerProvider(provider)
                Timber.d("Registered OpenClawProvider: $url")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to register OpenClawProvider")
        }
    }

    private suspend fun restoreActiveProviderSelection() {
        val activeId = preferences.observe(PreferenceKeys.ACTIVE_PROVIDER_ID).first() ?: return
        if (router.availableProviders.value.any { it.id == activeId }) {
            router.selectProvider(activeId)
        }
    }

    fun getModelManager(): ModelManager = modelManager
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.assistant.provider.ProviderManagerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/assistant/provider/ProviderManager.kt app/src/test/java/com/opendash/app/assistant/provider/ProviderManagerTest.kt
git commit -m "feat: mode-conditional provider registration, legacy migration, active-provider restore"
```

---

### Task 9: `ProviderModeScreen` + `ProviderModeViewModel`

This task must land before Task 10 (`MainActivity` mode gate), which imports `ProviderModeScreen` directly.

**Files:**
- Create: `app/src/main/java/com/opendash/app/ui/onboarding/ProviderModeViewModel.kt`
- Create: `app/src/main/java/com/opendash/app/ui/onboarding/ProviderModeScreen.kt`
- Test: `app/src/test/java/com/opendash/app/ui/onboarding/ProviderModeViewModelTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (new keys added in Task 15, but referenced here — see note below)

**Interfaces:**
- Produces: `ProviderModeViewModel.selectMode(mode: String)` (suspend-free public API backed by `viewModelScope`), reads/writes `PreferenceKeys.ASSISTANT_MODE` via `AppPreferences`. `ProviderModeScreen(onModeSelected: () -> Unit, viewModel: ProviderModeViewModel = hiltViewModel())`.

Note on strings: this task's Composable references `R.string.provider_mode_*` keys. Task 15 adds those keys to every locale file. Until Task 15 lands, `LocaleStringsParityTest` (part of `testDebugUnitTest`) will fail on drift — this task's Step 3 adds the English `values/strings.xml` entries only; Task 15 fans them out to the other 43 locales.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.ui.onboarding

import com.google.common.truth.Truth.assertThat
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderModeViewModelTest {

    private lateinit var appPreferences: AppPreferences
    private lateinit var viewModel: ProviderModeViewModel

    @BeforeEach
    fun setup() {
        appPreferences = mockk(relaxed = true)
        every { appPreferences.observe(PreferenceKeys.ASSISTANT_MODE) } returns flowOf(null)
        viewModel = ProviderModeViewModel(appPreferences)
    }

    @Test
    fun `selectMode local persists MODE_LOCAL`() = runTest {
        viewModel.selectMode(PreferenceKeys.MODE_LOCAL)

        coVerify { appPreferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_LOCAL) }
    }

    @Test
    fun `selectMode api persists MODE_API`() = runTest {
        viewModel.selectMode(PreferenceKeys.MODE_API)

        coVerify { appPreferences.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.ui.onboarding.ProviderModeViewModelTest"`
Expected: FAIL — `ProviderModeViewModel` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

`ProviderModeViewModel.kt`:

```kotlin
package com.opendash.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderModeViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    fun selectMode(mode: String) {
        viewModelScope.launch {
            appPreferences.set(PreferenceKeys.ASSISTANT_MODE, mode)
        }
    }
}
```

`ProviderModeScreen.kt`:

```kotlin
package com.opendash.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendash.app.R
import com.opendash.app.data.preferences.PreferenceKeys

/**
 * First-run choice between the embedded on-device model and a cloud API
 * provider. Shown once, before ModelSetupScreen/OnboardingScreen — see
 * MainActivity's mode gate. Selecting either mode persists it immediately;
 * MainActivity observes the same preference and advances automatically.
 */
@Composable
fun ProviderModeScreen(
    onModeSelected: () -> Unit,
    viewModel: ProviderModeViewModel = hiltViewModel()
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.provider_mode_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.provider_mode_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ModeOptionCard(
            title = stringResource(R.string.provider_mode_local_title),
            description = stringResource(R.string.provider_mode_local_description),
            onClick = {
                viewModel.selectMode(PreferenceKeys.MODE_LOCAL)
                onModeSelected()
            }
        )
        ModeOptionCard(
            title = stringResource(R.string.provider_mode_api_title),
            description = stringResource(R.string.provider_mode_api_description),
            onClick = {
                viewModel.selectMode(PreferenceKeys.MODE_API)
                onModeSelected()
            }
        )
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

Add the English strings to `app/src/main/res/values/strings.xml` (near the `<!-- Providers screen -->` block):

```xml
    <!-- Provider mode (first-run + settings) -->
    <string name="provider_mode_title">Choose how OpenDash thinks</string>
    <string name="provider_mode_subtitle">You can change this later in Settings.</string>
    <string name="provider_mode_local_title">Local LLM</string>
    <string name="provider_mode_local_description">Runs fully on this device. Works offline, but less capable than cloud models.</string>
    <string name="provider_mode_api_title">API provider</string>
    <string name="provider_mode_api_description">Connect OpenAI, Anthropic, or another cloud provider using your own API key. Requires internet.</string>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.ui.onboarding.ProviderModeViewModelTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/ui/onboarding/ProviderModeViewModel.kt app/src/main/java/com/opendash/app/ui/onboarding/ProviderModeScreen.kt app/src/test/java/com/opendash/app/ui/onboarding/ProviderModeViewModelTest.kt app/src/main/res/values/strings.xml
git commit -m "feat: add ProviderModeScreen for first-run local-vs-API choice"
```

Note: `./gradlew assembleDebug` will not fully succeed until Task 15 adds these keys to every locale file (`LocaleStringsParityTest` runs under `testDebugUnitTest`, not `assembleDebug`, so the debug APK itself will build — but the unit test suite will fail on parity until Task 15). Run `testDebugUnitTest --tests "*ProviderModeViewModelTest"` scoped as above to confirm this task in isolation; the full suite is green again after Task 15.

---

### Task 10: `MainActivity` mode gate

This task has no JVM unit test (this codebase has no `MainActivity` tests — Compose entry points are verified on-device, consistent with `docs/real-device-smoke-test.md`). Verification is a manual build + on-device check described in Step 3.

**Files:**
- Modify: `app/src/main/java/com/opendash/app/MainActivity.kt`

**Interfaces:**
- Consumes: `ProviderModeScreen` (Task 9), `PreferenceKeys.ASSISTANT_MODE`/`MODE_API` (Task 2).

- [ ] **Step 1: Locate the exact block to replace**

Current `onCreate` (relevant excerpt, `MainActivity.kt:99-144`):

```kotlin
        setContent {
            OpenDashTheme {
                val downloadState by modelDownloader.state.collectAsState()
                val setupCompleted by appPreferences
                    .observe(PreferenceKeys.SETUP_COMPLETED)
                    .collectAsState(initial = null)
                var onboardingDismissed by remember { mutableStateOf(false) }

                when (downloadState) {
                    is ModelDownloadState.Ready -> {
                        if (!providerInitialized) {
                            providerInitialized = true
                            providerManager.initialize()
                        }
                        val needsOnboarding = setupCompleted == false && !onboardingDismissed
                        if (needsOnboarding) {
                            OnboardingScreen(onDone = { onboardingDismissed = true })
                        } else {
                            ModeScaffold()
                        }
                    }
                    else -> {
                        val models by modelDownloader.availableModels.collectAsState()
                        val selected by modelDownloader.selectedModel.collectAsState()

                        ModelSetupScreen(
                            downloadState = modelDownloader.state,
                            selectedModel = selected,
                            availableModels = models,
                            onSelectModel = { modelDownloader.selectModel(it) },
                            onStartDownload = { scope.launch { modelDownloader.downloadSelectedModel() } },
                            onRetry = { scope.launch { modelDownloader.downloadSelectedModel() } }
                        )
                    }
                }
            }
        }

        requestPermissionsAndStart()
        scope.launch {
            if (modelDownloader.isModelDownloaded()) {
                modelDownloader.ensureModelAvailable()
            } else {
                modelDownloader.fetchAvailableModels()
            }
        }
```

- [ ] **Step 2: Replace with the mode-gated version**

```kotlin
        setContent {
            OpenDashTheme {
                val downloadState by modelDownloader.state.collectAsState()
                val assistantMode by appPreferences
                    .observe(PreferenceKeys.ASSISTANT_MODE)
                    .collectAsState(initial = null)
                val setupCompleted by appPreferences
                    .observe(PreferenceKeys.SETUP_COMPLETED)
                    .collectAsState(initial = null)
                var onboardingDismissed by remember { mutableStateOf(false) }
                var modeDismissed by remember { mutableStateOf(false) }

                when {
                    assistantMode == null && !modeDismissed -> {
                        ProviderModeScreen(onModeSelected = { modeDismissed = true })
                    }
                    assistantMode == PreferenceKeys.MODE_LOCAL && downloadState !is ModelDownloadState.Ready -> {
                        val models by modelDownloader.availableModels.collectAsState()
                        val selected by modelDownloader.selectedModel.collectAsState()

                        ModelSetupScreen(
                            downloadState = modelDownloader.state,
                            selectedModel = selected,
                            availableModels = models,
                            onSelectModel = { modelDownloader.selectModel(it) },
                            onStartDownload = { scope.launch { modelDownloader.downloadSelectedModel() } },
                            onRetry = { scope.launch { modelDownloader.downloadSelectedModel() } }
                        )
                    }
                    else -> {
                        if (!providerInitialized) {
                            providerInitialized = true
                            providerManager.initialize()
                        }
                        val needsOnboarding = setupCompleted == false && !onboardingDismissed
                        if (needsOnboarding) {
                            OnboardingScreen(onDone = { onboardingDismissed = true })
                        } else {
                            ModeScaffold()
                        }
                    }
                }
            }
        }

        requestPermissionsAndStart()
        scope.launch {
            val mode = appPreferences.observe(PreferenceKeys.ASSISTANT_MODE).first()
            if (mode != PreferenceKeys.MODE_API) {
                if (modelDownloader.isModelDownloaded()) {
                    modelDownloader.ensureModelAvailable()
                } else {
                    modelDownloader.fetchAvailableModels()
                }
            }
        }
```

Add the new import alongside the existing ones near the top of the file:

```kotlin
import com.opendash.app.ui.onboarding.ProviderModeScreen
import kotlinx.coroutines.flow.first
```

- [ ] **Step 3: Manual verification (no JVM test for MainActivity in this codebase)**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

On-device / emulator check (mirrors the existing `docs/real-device-smoke-test.md` pattern):
1. Clear app data (fresh install state).
2. Launch — `ProviderModeScreen` appears before `ModelSetupScreen`.
3. Choose "Local LLM" — `ModelSetupScreen` appears next, exactly as before this change.
4. Clear app data again, relaunch, choose "API" — `ModelSetupScreen` is skipped entirely; flow goes straight to `OnboardingScreen` (permissions) then `ModeScaffold`.
5. Confirm `./gradlew testDebugUnitTest` still passes in full (no regression in unrelated suites).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/opendash/app/MainActivity.kt
git commit -m "feat: gate first-run flow on assistant mode (local vs API) before model download"
```

---

### Task 11: `ApiProviderSetupViewModel`

**Files:**
- Create: `app/src/main/java/com/opendash/app/ui/settings/providers/ApiProviderSetupViewModel.kt`
- Test: `app/src/test/java/com/opendash/app/ui/settings/providers/ApiProviderSetupViewModelTest.kt`

**Interfaces:**
- Consumes: `ApiProviderCatalog` (Task 3), `ApiProviderConfigStore` (Task 2), `ModelListFetcher` (Task 7).
- Produces:
  ```kotlin
  data class ApiProviderSetupState(
      val selectedPreset: ApiProviderCatalog.Preset? = null,
      val displayName: String = "",
      val baseUrl: String = "",
      val apiKey: String = "",
      val availableModels: List<String> = emptyList(),
      val selectedModel: String = "",
      val isFetchingModels: Boolean = false,
      val modelFetchFailed: Boolean = false,
      val saved: Boolean = false
  )
  ```
  `ApiProviderSetupViewModel.state: StateFlow<ApiProviderSetupState>`, `.selectPreset(preset)`, `.updateApiKey(key)`, `.updateBaseUrl(url)`, `.updateDisplayName(name)`, `.fetchModels()`, `.selectModel(modelId)`, `.save()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.opendash.app.ui.settings.providers

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.opendash.app.assistant.provider.api.ApiProviderCatalog
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.api.ModelListFetcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApiProviderSetupViewModelTest {

    private lateinit var store: ApiProviderConfigStore
    private lateinit var fetcher: ModelListFetcher
    private lateinit var viewModel: ApiProviderSetupViewModel

    @BeforeEach
    fun setup() {
        store = mockk(relaxed = true)
        fetcher = mockk()
        viewModel = ApiProviderSetupViewModel(store, fetcher)
    }

    @Test
    fun `selecting a preset prefills display name and base url`() = runTest {
        viewModel.state.test {
            assertThat(awaitItem().selectedPreset).isNull()

            viewModel.selectPreset(ApiProviderCatalog.find("openai")!!)

            val state = awaitItem()
            assertThat(state.selectedPreset?.id).isEqualTo("openai")
            assertThat(state.displayName).isEqualTo("OpenAI")
            assertThat(state.baseUrl).isEqualTo("https://api.openai.com")
        }
    }

    @Test
    fun `fetchModels populates availableModels on success`() = runTest {
        coEvery { fetcher.fetch("https://api.openai.com", "sk-test", "bearer") } returns
            Result.success(listOf("gpt-5.5", "gpt-5.5-mini"))

        viewModel.selectPreset(ApiProviderCatalog.find("openai")!!)
        viewModel.updateApiKey("sk-test")

        viewModel.state.test {
            awaitItem() // initial after preset+key updates settle from setup above is racy;
            viewModel.fetchModels()
            val loading = awaitItem()
            assertThat(loading.isFetchingModels).isTrue()
            val loaded = awaitItem()
            assertThat(loaded.isFetchingModels).isFalse()
            assertThat(loaded.availableModels).containsExactly("gpt-5.5", "gpt-5.5-mini")
            assertThat(loaded.modelFetchFailed).isFalse()
        }
    }

    @Test
    fun `fetchModels sets modelFetchFailed on failure so UI can fall back to free text`() = runTest {
        coEvery { fetcher.fetch(any(), any(), any()) } returns Result.failure(RuntimeException("boom"))
        viewModel.selectPreset(ApiProviderCatalog.find("ollama")!!)

        viewModel.fetchModels()

        assertThat(viewModel.state.value.modelFetchFailed).isTrue()
        assertThat(viewModel.state.value.isFetchingModels).isFalse()
    }

    @Test
    fun `save persists a config and marks state saved`() = runTest {
        viewModel.selectPreset(ApiProviderCatalog.find("openai")!!)
        viewModel.updateApiKey("sk-test")
        viewModel.selectModel("gpt-5.5")

        viewModel.save()

        coVerify {
            store.add(
                match { it.presetId == "openai" && it.baseUrl == "https://api.openai.com" && it.modelId == "gpt-5.5" && it.authStyle == "bearer" },
                "sk-test"
            )
        }
        assertThat(viewModel.state.value.saved).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.ui.settings.providers.ApiProviderSetupViewModelTest"`
Expected: FAIL — `ApiProviderSetupViewModel` doesn't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.opendash.app.ui.settings.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.assistant.provider.api.ApiProviderCatalog
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import com.opendash.app.assistant.provider.api.ModelListFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ApiProviderSetupState(
    val selectedPreset: ApiProviderCatalog.Preset? = null,
    val displayName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val availableModels: List<String> = emptyList(),
    val selectedModel: String = "",
    val isFetchingModels: Boolean = false,
    val modelFetchFailed: Boolean = false,
    val saved: Boolean = false
)

@HiltViewModel
class ApiProviderSetupViewModel @Inject constructor(
    private val store: ApiProviderConfigStore,
    private val fetcher: ModelListFetcher
) : ViewModel() {

    private val _state = MutableStateFlow(ApiProviderSetupState())
    val state: StateFlow<ApiProviderSetupState> = _state.asStateFlow()

    fun selectPreset(preset: ApiProviderCatalog.Preset) {
        _state.update {
            it.copy(
                selectedPreset = preset,
                displayName = preset.displayName,
                baseUrl = preset.defaultBaseUrl,
                availableModels = emptyList(),
                selectedModel = "",
                modelFetchFailed = false
            )
        }
    }

    fun updateDisplayName(name: String) {
        _state.update { it.copy(displayName = name) }
    }

    fun updateBaseUrl(url: String) {
        _state.update { it.copy(baseUrl = url) }
    }

    fun updateApiKey(key: String) {
        _state.update { it.copy(apiKey = key) }
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(selectedModel = modelId) }
    }

    fun fetchModels() {
        val current = _state.value
        val authStyle = current.selectedPreset?.authStyle ?: "bearer"
        viewModelScope.launch {
            _state.update { it.copy(isFetchingModels = true, modelFetchFailed = false) }
            val result = fetcher.fetch(current.baseUrl, current.apiKey, authStyle)
            result.fold(
                onSuccess = { models ->
                    _state.update {
                        it.copy(isFetchingModels = false, availableModels = models, modelFetchFailed = false)
                    }
                },
                onFailure = {
                    _state.update { it.copy(isFetchingModels = false, modelFetchFailed = true) }
                }
            )
        }
    }

    fun save() {
        val current = _state.value
        val preset = current.selectedPreset ?: return
        viewModelScope.launch {
            val config = ApiProviderConfig(
                id = UUID.randomUUID().toString(),
                presetId = preset.id,
                displayName = current.displayName,
                baseUrl = current.baseUrl,
                modelId = current.selectedModel,
                authStyle = preset.authStyle,
                createdAt = java.lang.System.currentTimeMillis()
            )
            store.add(config, current.apiKey)
            _state.update { it.copy(saved = true) }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.ui.settings.providers.ApiProviderSetupViewModelTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/opendash/app/ui/settings/providers/ApiProviderSetupViewModel.kt app/src/test/java/com/opendash/app/ui/settings/providers/ApiProviderSetupViewModelTest.kt
git commit -m "feat: add ApiProviderSetupViewModel for the add-provider flow"
```

---

### Task 12: `ApiProviderSetupScreen` (preset picker + add-provider dialog)

No JVM test for this Composable, consistent with the rest of this codebase's UI layer (see Task 10's note). Verified via `assembleDebug` + manual check.

**Files:**
- Create: `app/src/main/java/com/opendash/app/ui/settings/providers/ApiProviderSetupScreen.kt`

**Interfaces:**
- Consumes: `ApiProviderSetupViewModel` (Task 11), `ApiProviderCatalog.presets` (Task 3).
- Produces: `@Composable fun AddApiProviderDialog(onDismiss: () -> Unit, onSaved: () -> Unit, viewModel: ApiProviderSetupViewModel = hiltViewModel())`.

- [ ] **Step 1: Write the composable**

```kotlin
package com.opendash.app.ui.settings.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendash.app.R
import com.opendash.app.assistant.provider.api.ApiProviderCatalog

@Composable
fun AddApiProviderDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ApiProviderSetupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var presetMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_provider_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = presetMenuExpanded,
                    onExpandedChange = { presetMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = state.selectedPreset?.displayName ?: stringResource(R.string.add_provider_select_preset),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.add_provider_select_preset)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuExpanded) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = presetMenuExpanded,
                        onDismissRequest = { presetMenuExpanded = false }
                    ) {
                        ApiProviderCatalog.presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = {
                                    viewModel.selectPreset(preset)
                                    presetMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = viewModel::updateDisplayName,
                    label = { Text(stringResource(R.string.add_provider_display_name_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    label = { Text(stringResource(R.string.add_provider_base_url_label)) },
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.selectedPreset?.requiresApiKey == true) {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = viewModel::updateApiKey,
                        label = { Text(stringResource(R.string.add_provider_api_key_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(onClick = { viewModel.fetchModels() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.add_provider_fetch_models))
                }

                if (state.isFetchingModels) {
                    CircularProgressIndicator()
                } else if (state.availableModels.isNotEmpty()) {
                    LazyColumn {
                        items(state.availableModels) { modelId ->
                            DropdownMenuItem(
                                text = { Text(modelId) },
                                onClick = { viewModel.selectModel(modelId) }
                            )
                        }
                    }
                } else if (state.modelFetchFailed) {
                    OutlinedTextField(
                        value = state.selectedModel,
                        onValueChange = viewModel::selectModel,
                        label = { Text(stringResource(R.string.add_provider_model_fetch_failed)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.save() },
                enabled = state.selectedPreset != null && state.selectedModel.isNotBlank()
            ) {
                Text(stringResource(R.string.add_provider_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_provider_cancel)) }
        }
    )
}
```

Add the new English strings to `app/src/main/res/values/strings.xml` (same block as Task 9's additions):

```xml
    <string name="add_provider_title">Add provider</string>
    <string name="add_provider_select_preset">Select provider</string>
    <string name="add_provider_display_name_label">Display name</string>
    <string name="add_provider_base_url_label">Base URL</string>
    <string name="add_provider_api_key_label">API key</string>
    <string name="add_provider_fetch_models">Fetch models</string>
    <string name="add_provider_model_fetch_failed">Model ID (enter manually)</string>
    <string name="add_provider_save">Save</string>
    <string name="add_provider_cancel">Cancel</string>
```

- [ ] **Step 2: Verify the module compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/opendash/app/ui/settings/providers/ApiProviderSetupScreen.kt app/src/main/res/values/strings.xml
git commit -m "feat: add AddApiProviderDialog UI for the preset picker + model discovery"
```

---

### Task 13: `ProvidersScreen` — Mode card + Add Provider entry point

**Files:**
- Modify: `app/src/main/java/com/opendash/app/ui/settings/providers/ProvidersViewModel.kt`
- Modify: `app/src/main/java/com/opendash/app/ui/settings/providers/ProvidersScreen.kt`
- Test: `app/src/test/java/com/opendash/app/ui/settings/providers/ProvidersViewModelTest.kt` (extend existing)

**Interfaces:**
- Produces: `ProvidersViewModel.assistantMode: StateFlow<String?>`, `.hasConfiguredApiProviders: StateFlow<Boolean>`, `.setMode(mode: String)`.

- [ ] **Step 1: Write the failing test**

The existing `ProvidersViewModelTest.kt` constructs `ProvidersViewModel` three ways: via the `buildVm(...)` helper (used by most tests, with its own defaults) and via three direct `ProvidersViewModel(router, prefs, secure, discovery, tracker)` calls (the multi-room tests at what are currently lines 115, 139, and 158). All four call sites need a 6th argument once the constructor changes. Apply this exact diff:

Add two imports (the file already imports `io.mockk.mockk`, `io.mockk.every`, `io.mockk.coEvery`, `io.mockk.coVerify`, `kotlinx.coroutines.flow.flowOf` — do not duplicate those):

```kotlin
import com.opendash.app.assistant.provider.api.ApiProviderConfig
import com.opendash.app.assistant.provider.api.ApiProviderConfigStore
import kotlinx.coroutines.flow.first
```

Change the three direct-construction call sites from:

```kotlin
        val vm = ProvidersViewModel(router, prefs, secure, discovery, tracker)
```

to:

```kotlin
        val vm = ProvidersViewModel(router, prefs, secure, discovery, tracker, emptyApiProviderConfigStore())
```

(all three occurrences — the multi-room tests at `multiroomState reports healthy mesh...`, `multiroomState hides broadcastingAs...`, and `multiroomState falls back to mDNS speakers...`).

Change `buildVm` from:

```kotlin
    private fun buildVm(
        router: ConversationRouter = idleRouter(),
        prefs: AppPreferences = prefsWith(),
        secure: SecurePreferences = emptySecurePrefs(),
        discovery: MulticastDiscovery = emptyDiscovery(),
        tracker: PeerLivenessTracker = PeerLivenessTracker()
    ) = ProvidersViewModel(router, prefs, secure, discovery, tracker)
```

to:

```kotlin
    private fun buildVm(
        router: ConversationRouter = idleRouter(),
        prefs: AppPreferences = prefsWith(),
        secure: SecurePreferences = emptySecurePrefs(),
        discovery: MulticastDiscovery = emptyDiscovery(),
        tracker: PeerLivenessTracker = PeerLivenessTracker(),
        apiProviderConfigStore: ApiProviderConfigStore = emptyApiProviderConfigStore()
    ) = ProvidersViewModel(router, prefs, secure, discovery, tracker, apiProviderConfigStore)

    private fun emptyApiProviderConfigStore(): ApiProviderConfigStore {
        val store = mockk<ApiProviderConfigStore>()
        coEvery { store.list() } returns emptyList()
        return store
    }
```

Then append the new tests, using `buildVm`'s new `apiProviderConfigStore` parameter:

```kotlin
    @Test
    fun `assistantMode reflects the stored preference`() = runTest {
        val prefs = prefsWith(Pair(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_API))

        val vm = buildVm(prefs = prefs)
        advanceUntilIdle()

        assertThat(vm.assistantMode.first()).isEqualTo(PreferenceKeys.MODE_API)
    }

    @Test
    fun `hasConfiguredApiProviders is true when at least one config exists`() = runTest {
        val store = mockk<ApiProviderConfigStore>()
        coEvery { store.list() } returns listOf(
            ApiProviderConfig(
                id = "x", presetId = "openai", displayName = "OpenAI",
                baseUrl = "https://api.openai.com", modelId = "gpt-5.5",
                authStyle = "bearer", createdAt = 1L
            )
        )

        val vm = buildVm(apiProviderConfigStore = store)
        advanceUntilIdle()

        assertThat(vm.hasConfiguredApiProviders.first()).isTrue()
    }

    @Test
    fun `setMode persists the chosen mode`() = runTest {
        val prefs = mockk<AppPreferences>(relaxed = true)
        every { prefs.observe<Any>(any()) } returns flowOf(null)

        val vm = buildVm(prefs = prefs)
        vm.setMode(PreferenceKeys.MODE_LOCAL)
        advanceUntilIdle()

        coVerify { prefs.set(PreferenceKeys.ASSISTANT_MODE, PreferenceKeys.MODE_LOCAL) }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.ui.settings.providers.ProvidersViewModelTest"`
Expected: FAIL — `ProvidersViewModel` constructor doesn't take `apiProviderConfigStore`, and `assistantMode`/`hasConfiguredApiProviders`/`setMode` don't exist.

- [ ] **Step 3: Write minimal implementation**

In `ProvidersViewModel.kt`, add the constructor param and three new members (everything else in the file — `rows`, `multiroomState`, `select`, `toRow` — stays unchanged):

```kotlin
@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val router: ConversationRouter,
    private val appPreferences: AppPreferences,
    private val securePreferences: SecurePreferences,
    private val discovery: MulticastDiscovery,
    private val liveness: PeerLivenessTracker,
    private val apiProviderConfigStore: com.opendash.app.assistant.provider.api.ApiProviderConfigStore
) : ViewModel() {

    // ... existing Row / MultiroomState data classes, rows, multiroomState unchanged ...

    val assistantMode: StateFlow<String?> = appPreferences
        .observe(PreferenceKeys.ASSISTANT_MODE)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasConfiguredApiProviders: StateFlow<Boolean> = kotlinx.coroutines.flow.flow {
        emit(apiProviderConfigStore.list().isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMode(mode: String) {
        viewModelScope.launch {
            appPreferences.set(PreferenceKeys.ASSISTANT_MODE, mode)
        }
    }

    fun select(providerId: String) {
        viewModelScope.launch {
            router.selectProvider(providerId)
        }
    }

    // ... existing toRow() unchanged ...
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.ui.settings.providers.ProvidersViewModelTest"`
Expected: PASS (all existing tests + 3 new ones).

- [ ] **Step 5: Update `ProvidersScreen.kt` to render the mode card + add-provider entry**

Add a `ModeCard` composable and wire it into the existing `LazyColumn`, plus a dialog-visibility flag for `AddApiProviderDialog`:

```kotlin
@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val multiroom by viewModel.multiroomState.collectAsStateWithLifecycle()
    val assistantMode by viewModel.assistantMode.collectAsStateWithLifecycle()
    val hasConfiguredApiProviders by viewModel.hasConfiguredApiProviders.collectAsStateWithLifecycle()
    var showAddProviderDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (showAddProviderDialog) {
        com.opendash.app.ui.settings.providers.AddApiProviderDialog(
            onDismiss = { showAddProviderDialog = false },
            onSaved = { showAddProviderDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            item(key = "__mode__") {
                ModeCard(
                    mode = assistantMode,
                    hasConfiguredApiProviders = hasConfiguredApiProviders,
                    onSelectLocal = { viewModel.setMode(PreferenceKeys.MODE_LOCAL) },
                    onSelectApi = { viewModel.setMode(PreferenceKeys.MODE_API) },
                    onAddProvider = { showAddProviderDialog = true }
                )
            }
            if (rows.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        stringResource(R.string.providers_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(rows, key = { it.id }) { row ->
                    ProviderRow(row = row, onSelect = { viewModel.select(row.id) })
                }
            }
            item(key = "__multiroom__") {
                Spacer(Modifier.size(8.dp))
                MultiroomCard(state = multiroom)
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: String?,
    hasConfiguredApiProviders: Boolean,
    onSelectLocal: () -> Unit,
    onSelectApi: () -> Unit,
    onAddProvider: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.providers_mode_card_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSelectLocal,
                    colors = if (mode == PreferenceKeys.MODE_LOCAL) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) { Text(stringResource(R.string.providers_mode_local)) }
                Button(
                    onClick = {
                        if (hasConfiguredApiProviders) onSelectApi() else onAddProvider()
                    },
                    colors = if (mode == PreferenceKeys.MODE_API) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) { Text(stringResource(R.string.providers_mode_api)) }
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onAddProvider) {
                Text(stringResource(R.string.providers_add_provider))
            }
        }
    }
}
```

Add the required imports to `ProvidersScreen.kt` (`Button`, `ButtonDefaults`, `com.opendash.app.data.preferences.PreferenceKeys`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.mutableStateOf`) and add the three new English strings:

```xml
    <string name="providers_mode_card_title">Assistant mode</string>
    <string name="providers_mode_local">Local LLM</string>
    <string name="providers_mode_api">API</string>
    <string name="providers_add_provider">Add provider</string>
```

- [ ] **Step 6: Verify the module compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/opendash/app/ui/settings/providers/ProvidersViewModel.kt app/src/main/java/com/opendash/app/ui/settings/providers/ProvidersScreen.kt app/src/test/java/com/opendash/app/ui/settings/providers/ProvidersViewModelTest.kt app/src/main/res/values/strings.xml
git commit -m "feat: add mode card and add-provider entry point to ProvidersScreen"
```

---

### Task 14: `network_security_config.xml` for local/custom cleartext endpoints

The Ollama and LM Studio presets default to `http://localhost:...`. Android 9+ blocks cleartext HTTP by default, and this app has no network security config today, so those two presets would silently fail every request.

**Files:**
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create the config**

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!--
        Default: HTTPS only, matching the rest of the app's providers
        (OpenAI, Anthropic, Groq, etc. are all HTTPS).
    -->
    <base-config cleartextTrafficPermitted="false" />

    <!--
        Local-network exceptions for the Ollama / LM Studio presets and any
        "custom" provider the user points at their own LAN server. 10.0.2.2
        is the Android emulator's host-loopback alias.
    -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 2: Wire it into the manifest**

In `AndroidManifest.xml`, add `android:networkSecurityConfig` to the `<application>` tag (`AndroidManifest.xml:152`):

```xml
    <application
        android:name=".OpenDashApp"
        android:allowBackup="true"
        android:networkSecurityConfig="@xml/network_security_config"
        android:icon="@mipmap/ic_launcher"
```

- [ ] **Step 3: Verify the build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Note: this only covers `localhost`/`127.0.0.1`/the emulator alias. A user pointing "custom" at an arbitrary LAN IP (e.g. `192.168.1.50`) still needs HTTPS or their own device to be added to this allowlist — that's a known limitation, not silently broken (the request fails loudly with a `CLEARTEXT communication not permitted` exception surfaced by `ErrorClassifier`, not a hang). Expanding this to arbitrary private IP ranges is a reasonable follow-up if users hit it in practice, but is not required for the Ollama/LM Studio presets to work on a real device or emulator.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/network_security_config.xml app/src/main/AndroidManifest.xml
git commit -m "feat: allow cleartext HTTP for localhost/127.0.0.1 for local LLM server presets"
```

---

### Task 15: i18n — fan out new strings to all 44 locales

Tasks 10, 12, and 13 added these English keys to `values/strings.xml`:

```
provider_mode_title, provider_mode_subtitle, provider_mode_local_title,
provider_mode_local_description, provider_mode_api_title, provider_mode_api_description,
add_provider_title, add_provider_select_preset, add_provider_display_name_label,
add_provider_base_url_label, add_provider_api_key_label, add_provider_fetch_models,
add_provider_model_fetch_failed, add_provider_save, add_provider_cancel,
providers_mode_card_title, providers_mode_local, providers_mode_api, providers_add_provider
```

`LocaleStringsParityTest` (`app/src/test/java/com/opendash/app/util/LocaleStringsParityTest.kt`) fails the build until every `values-*/strings.xml` file declares the exact same 19 names. This task adds them, translated, to all 44 locale directories.

**Files:**
- Modify: every `app/src/main/res/values-*/strings.xml` (44 files — full list from `ls app/src/main/res | grep '^values-[a-z]'`: `ar, bg, bn, ca, cs, da, de, el, es, et, fa, fi, fil, fr, hi, hr, hu, in, it, iw, ja, kk, ko, lt, lv, ms, nb, nl, pl, pt-rBR, ro, ru, sk, sl, sr, sv, ta, th, tr, uk, ur, vi, zh-rCN, zh-rTW`)

**Interfaces:** none (resource-only task).

- [ ] **Step 1: Add the fully-translated Japanese block (worked example)**

Add to `app/src/main/res/values-ja/strings.xml`:

```xml
    <!-- Provider mode (first-run + settings) -->
    <string name="provider_mode_title">OpenDashの動作方法を選択</string>
    <string name="provider_mode_subtitle">この設定は後で設定画面から変更できます。</string>
    <string name="provider_mode_local_title">ローカルLLM</string>
    <string name="provider_mode_local_description">この端末上で完結します。オフラインで動作しますが、クラウドモデルより性能は劣ります。</string>
    <string name="provider_mode_api_title">APIプロバイダー</string>
    <string name="provider_mode_api_description">自分のAPIキーでOpenAI、Anthropicなどのクラウドプロバイダーに接続します。インターネット接続が必要です。</string>
    <string name="add_provider_title">プロバイダーを追加</string>
    <string name="add_provider_select_preset">プロバイダーを選択</string>
    <string name="add_provider_display_name_label">表示名</string>
    <string name="add_provider_base_url_label">ベースURL</string>
    <string name="add_provider_api_key_label">APIキー</string>
    <string name="add_provider_fetch_models">モデル一覧を取得</string>
    <string name="add_provider_model_fetch_failed">モデルIDを直接入力</string>
    <string name="add_provider_save">保存</string>
    <string name="add_provider_cancel">キャンセル</string>
    <string name="providers_mode_card_title">アシスタントモード</string>
    <string name="providers_mode_local">ローカルLLM</string>
    <string name="providers_mode_api">API</string>
    <string name="providers_add_provider">プロバイダーを追加</string>
```

- [ ] **Step 2: Add the same 19 keys, translated, to every remaining locale file**

For each of the other 43 `values-*/strings.xml` files, add a block with the same 19 `name=` attributes, with `<string>` text translated into that locale's language (do not leave English text in a non-English file — that's what the parity test's sibling convention in `docs/i18n.md` exists to prevent). Use the English source text below as the basis for translation into each locale:

```xml
    <string name="provider_mode_title">Choose how OpenDash thinks</string>
    <string name="provider_mode_subtitle">You can change this later in Settings.</string>
    <string name="provider_mode_local_title">Local LLM</string>
    <string name="provider_mode_local_description">Runs fully on this device. Works offline, but less capable than cloud models.</string>
    <string name="provider_mode_api_title">API provider</string>
    <string name="provider_mode_api_description">Connect OpenAI, Anthropic, or another cloud provider using your own API key. Requires internet.</string>
    <string name="add_provider_title">Add provider</string>
    <string name="add_provider_select_preset">Select provider</string>
    <string name="add_provider_display_name_label">Display name</string>
    <string name="add_provider_base_url_label">Base URL</string>
    <string name="add_provider_api_key_label">API key</string>
    <string name="add_provider_fetch_models">Fetch models</string>
    <string name="add_provider_model_fetch_failed">Model ID (enter manually)</string>
    <string name="add_provider_save">Save</string>
    <string name="add_provider_cancel">Cancel</string>
    <string name="providers_mode_card_title">Assistant mode</string>
    <string name="providers_mode_local">Local LLM</string>
    <string name="providers_mode_api">API</string>
    <string name="providers_add_provider">Add provider</string>
```

Do this for: `values-ar`, `values-bg`, `values-bn`, `values-ca`, `values-cs`, `values-da`, `values-de`, `values-el`, `values-es`, `values-et`, `values-fa`, `values-fi`, `values-fil`, `values-fr`, `values-hi`, `values-hr`, `values-hu`, `values-in`, `values-it`, `values-iw`, `values-kk`, `values-ko`, `values-lt`, `values-lv`, `values-ms`, `values-nb`, `values-nl`, `values-pl`, `values-pt-rBR`, `values-ro`, `values-ru`, `values-sk`, `values-sl`, `values-sr`, `values-sv`, `values-ta`, `values-th`, `values-tr`, `values-uk`, `values-ur`, `values-vi`, `values-zh-rCN`, `values-zh-rTW`.

`values-ar` and `values-iw` are RTL locales (Arabic, Hebrew) — translate the text normally; RTL layout mirroring is handled by the system based on the string content's script and the existing RTL support already in the app (no special markup needed per the existing strings in those files).

- [ ] **Step 3: Run the parity test**

Run: `./gradlew testDebugUnitTest --tests "com.opendash.app.util.LocaleStringsParityTest"`
Expected: PASS — zero drift entries.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values-*/strings.xml
git commit -m "i18n: add provider mode + add-provider strings to all 44 locales"
```

---

### Task 16: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures (this includes every test added in Tasks 1–13 plus `LocaleStringsParityTest` from Task 15, plus every pre-existing test in the suite — no regressions).

- [ ] **Step 2: Run the debug build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run lint**

Run: `./gradlew lint`
Expected: no new issues beyond the existing `lint-baseline.xml` (per `CLAUDE.md`'s baseline convention — if new warnings appear, fix them rather than regenerating the baseline to hide them).

- [ ] **Step 4: On-device / emulator smoke check**

Follow Task 10 Step 3's manual checklist end-to-end: fresh install → mode picker → Local path (unchanged behavior) → fresh install → API path (skips model download) → Settings → Providers → mode card toggle + Add Provider dialog → save an OpenAI-preset config with a fake key (expect model fetch to fail gracefully to the free-text fallback, since there's no real key) → confirm the new row appears in the provider list and can be selected.

- [ ] **Step 5: Update the roadmap**

Add an entry to `docs/roadmap.md` under a new or existing Phase, noting this PR ships API-key-only provider mode selection and that GitHub Copilot device-code OAuth (Tier 2) and user-registered PKCE OAuth (Tier 3) are tracked as follow-ups per the design spec.

- [ ] **Step 6: Final commit**

```bash
git add docs/roadmap.md
git commit -m "docs: record provider mode selection in roadmap, note OAuth follow-ups"
```
