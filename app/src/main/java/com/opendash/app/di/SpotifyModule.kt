package com.opendash.app.di

import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.SecurePreferences
import com.opendash.app.tool.spotify.DefaultSpotifyApiClient
import com.opendash.app.tool.spotify.DefaultSpotifyAuthManager
import com.opendash.app.tool.spotify.SpotifyApiClient
import com.opendash.app.tool.spotify.SpotifyAuthManager
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * [SpotifyAuthManager] is shared across [com.opendash.app.tool.spotify.SpotifyAuthCallbackActivity],
 * [com.opendash.app.ui.settings.spotify.SpotifySettingsViewModel], and the
 * [com.opendash.app.tool.spotify.SpotifyToolExecutor] registered in
 * [DeviceModule] — a proper singleton binding here, unlike the
 * tool-local instantiation most other tool dependencies use, since
 * those three consumers must observe the same connection state.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpotifyModule {

    @Provides
    @Singleton
    fun provideSpotifyAuthManager(
        appPreferences: AppPreferences,
        securePreferences: SecurePreferences,
        client: OkHttpClient,
        moshi: Moshi
    ): SpotifyAuthManager = DefaultSpotifyAuthManager(appPreferences, securePreferences, client, moshi)

    @Provides
    @Singleton
    fun provideSpotifyApiClient(
        authManager: SpotifyAuthManager,
        client: OkHttpClient,
        moshi: Moshi
    ): SpotifyApiClient = DefaultSpotifyApiClient(authManager, client, moshi)
}
