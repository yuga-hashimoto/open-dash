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
