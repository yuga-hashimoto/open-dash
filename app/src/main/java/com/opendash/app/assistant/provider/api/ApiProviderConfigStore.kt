package com.opendash.app.assistant.provider.api

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import com.opendash.app.data.preferences.SecurePreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first
import timber.log.Timber
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
        return try {
            adapter.fromJson(json).orEmpty()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse stored ApiProviderConfig list")
            emptyList()
        }
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
