package com.example.myapplication

import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import androidx.work.WorkManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.myapplication.di.appModule
import com.example.myapplication.di.databaseModule
import com.example.myapplication.di.viewModelModule
import com.example.myapplication.di.audiobookModule
import com.example.myapplication.network.di.coreNetworkModule

class VetroApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VetroApplication)
            modules(
                coreNetworkModule,
                com.example.myapplication.sync.supabase.supabaseModule,
                appModule,
                audiobookModule,
                com.example.myapplication.di.enrichmentModule,
                com.example.myapplication.di.whisperModule,
                com.example.myapplication.di.remotePlaybackModule,
                databaseModule,
                viewModelModule
            )
        }
        // Ничего ниже не нужно для первого кадра. Раньше это шло прямо здесь, на главном потоке до
        // первого кадра: инициализация WorkManager (своя БД), сборка координатора объяснений с
        // AI-цепочкой и шифрованным хранилищем и его подписка на всю коллекцию — вторым полным
        // чтением БД параллельно с главной. Теперь — в фоне, когда старт уже позади.
        val koin = org.koin.core.context.GlobalContext.get()
        koin.get<AppScope>().launch {
            delay(DEFERRED_STARTUP_MS)
            WorkManager.getInstance(this@VetroApplication).cancelUniqueWork("AiRecommendationWork")
            // Фоновые AI-объяснения статистики: живут независимо от открытия шторки (кэш по
            // отпечатку коллекции, поэтому отложенный старт ничего не теряет).
            koin.get<com.example.myapplication.domain.stats.StatsExplanationCoordinator>().start()
            // Live Maintenance (обогащение коллекции): периодика раз в 6 ч, если фича включена
            // (по умолчанию — да). Постановка с KEEP — повторный вызов безвреден.
            koin.get<com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator>()
                .ensureScheduled()
            // Автообновление: только в сборке, подписанной ключом автора (иначе расписание снимается).
            com.example.myapplication.update.AppUpdateScheduler.sync(
                this@VetroApplication,
                koin.get<com.example.myapplication.update.UpdatePolicy>().mode,
            )
            koin.get<com.example.myapplication.update.AppUpdateManager>().refreshInBackground()
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Страницы манги идут через свой загрузчик — со своим дисковым кэшем и регион-декодером
        // (см. MangaImageLoader), чтобы глава не вытесняла обложки коллекции.
        return ImageLoader.Builder(context)
            // Картинки — через корневой OkHttp (общий пул соединений с остальной сетью), а не через
            // отдельный клиент, который Coil создаёт сам.
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { org.koin.core.context.GlobalContext.get().get<okhttp3.OkHttpClient>() },
                    ),
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB
                    .build()
            }
            .build()
    }
}

/** Через сколько после старта процесса запускать фоновые координаторы. */
private const val DEFERRED_STARTUP_MS = 5_000L
