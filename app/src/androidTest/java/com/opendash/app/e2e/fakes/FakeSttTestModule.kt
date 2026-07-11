package com.opendash.app.e2e.fakes

import android.content.Context
import com.opendash.app.di.SttModule
import com.opendash.app.voice.stt.SpeechToText
import com.opendash.app.voice.stt.whisper.WhisperModelDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [SttModule] in instrumented tests so the production
 * [com.opendash.app.voice.stt.DelegatingSttProvider] (which would talk to
 * Android's `SpeechRecognizer`) is swapped for a programmatic
 * [FakeSpeechToText].
 *
 * Tests inject `FakeSpeechToText` directly via
 * `@Inject lateinit var fakeStt: FakeSpeechToText` — the binding below
 * registers the same singleton against both [SpeechToText] and the
 * concrete fake type.
 *
 * [WhisperModelDownloader] is also provided here (the real class, not
 * a fake) — [SttModule] normally provides it too, but `replaces =
 * [SttModule::class]` above drops that binding along with the rest of
 * the module. `OfflineStackViewModel`/`WhisperSettingsViewModel` inject
 * it directly (concrete class, not an interface), so the androidTest
 * Hilt graph needs *some* binding for it. The real downloader is safe
 * to provide as-is: its constructor only takes a `Context` and it
 * makes no network call until `.download()` is explicitly invoked,
 * which no current instrumented test does.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [SttModule::class]
)
object FakeSttTestModule {

    @Provides
    @Singleton
    fun provideFakeStt(): FakeSpeechToText = FakeSpeechToText()

    @Provides
    @Singleton
    fun provideSpeechToText(fake: FakeSpeechToText): SpeechToText = fake

    @Provides
    @Singleton
    fun provideWhisperModelDownloader(@ApplicationContext context: Context): WhisperModelDownloader =
        WhisperModelDownloader(context)
}
