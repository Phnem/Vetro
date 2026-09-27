package com.example.myapplication.di

import com.example.myapplication.AppScope
import com.example.myapplication.media.subtitles.whisper.AudioExtractor
import com.example.myapplication.media.subtitles.whisper.SpeechEngine
import com.example.myapplication.media.subtitles.whisper.WhisperCppEngine
import com.example.myapplication.media.subtitles.whisper.WhisperDeviceProfile
import com.example.myapplication.media.subtitles.whisper.WhisperModelStore
import com.example.myapplication.media.subtitles.whisper.WhisperSubtitleCache
import com.example.myapplication.media.subtitles.whisper.WhisperSubtitleManager
import java.io.File
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Локальные субтитры (Whisper): модели в `filesDir/whisper` (их удаляют из настроек, система их не
 * чистит), готовые субтитры — в `filesDir/whisper-subtitles`, временный звук — в кэше.
 */
val whisperModule = module {
    single { WhisperModelStore(File(androidContext().filesDir, "whisper"), get<OkHttpClient>(), get<AppScope>()) }
    single<SpeechEngine> { WhisperCppEngine() }
    single { AudioExtractor(androidContext(), File(androidContext().cacheDir, "whisper-audio")) }
    single { WhisperSubtitleCache(File(androidContext().filesDir, "whisper-subtitles")) }
    single {
        val extractor = get<AudioExtractor>()
        WhisperSubtitleManager(
            extract = { request, range -> extractor.extract(request.mediaItem, request.sourceFactory, range.first, range.last + 1) },
            engine = get(),
            cache = get(),
            scope = get<AppScope>(),
            threads = WhisperDeviceProfile.threads(Runtime.getRuntime().availableProcessors()),
        )
    }
}
