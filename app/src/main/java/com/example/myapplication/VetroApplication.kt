package com.example.myapplication

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
                databaseModule,
                viewModelModule
            )
        }
        WorkManager.getInstance(this).cancelUniqueWork("AiRecommendationWork")
        // Фоновые AI-объяснения статистики: живут независимо от открытия шторки
        org.koin.core.context.GlobalContext.get()
            .get<com.example.myapplication.domain.stats.StatsExplanationCoordinator>()
            .start()
        // Live Maintenance (обогащение коллекции): периодика раз в 6 ч, если фича включена (по умолчанию — да)
        org.koin.core.context.GlobalContext.get()
            .get<com.example.myapplication.domain.enrichment.CollectionEnrichmentCoordinator>()
            .ensureScheduled()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Страницы манги идут через свой загрузчик — со своим дисковым кэшем и регион-декодером
        // (см. MangaImageLoader), чтобы глава не вытесняла обложки коллекции.
        return ImageLoader.Builder(context)
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
