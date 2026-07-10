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
                client.newCall(requestBuilder.build()).execute().use { response ->
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
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
