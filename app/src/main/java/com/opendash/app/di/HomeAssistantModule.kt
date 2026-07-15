package com.opendash.app.di

import com.opendash.app.homeassistant.client.HomeAssistantClient
import com.opendash.app.homeassistant.client.HomeAssistantRestClient
import com.opendash.app.device.settings.DeviceSettingsRepository
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HomeAssistantModule {

    @Provides
    @Singleton
    fun provideHomeAssistantClient(
        client: OkHttpClient,
        moshi: Moshi,
        settingsRepository: DeviceSettingsRepository
    ): HomeAssistantClient = HomeAssistantRestClient(client, moshi, settingsRepository)
}
