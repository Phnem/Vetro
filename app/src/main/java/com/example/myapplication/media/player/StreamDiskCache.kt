package com.example.myapplication.media.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Дисковый кэш прочитанных диапазонов http-потоков плеера: повторная перемотка и пересмотр берут
 * данные с диска. Плеер уже спрашивает кэш здесь, но [ENABLED] выключен - поведение приложения
 * прежнее, на диск ничего не пишется.
 *
 * VETRO-TAIL[stream-cache]: что нужно до включения (docs/adr/0004-media-and-state-tools.md):
 * - проверить на настоящих источниках HLS и DASH, смену дорожки и качества;
 * - для подписанных и меняющихся адресов задать ключ кэша (`CacheKeyFactory`), иначе повторов не будет,
 *   а для адресов с авторизацией один ключ не должен отдавать чужое содержимое;
 * - не кэшировать живые плейлисты;
 * - измерить расход батареи и износ диска, дать способ очистить кэш.
 */
internal object StreamDiskCache {

    /** Единственный выключатель. */
    private const val ENABLED = false

    /** Кэш пишется фрагментами по 5 МБ, лимит должен вмещать много фрагментов. */
    private const val LIMIT_BYTES = 256L * 1024 * 1024

    /** Один экземпляр на процесс: два `SimpleCache` в одной папке портят друг другу индекс. */
    private var instance: Cache? = null

    /** Кэш для сессии плеера; null - играть без кэша. */
    fun forPlayback(context: Context): Cache? = if (ENABLED) open(context.applicationContext) else null

    @Synchronized
    private fun open(context: Context): Cache = instance ?: SimpleCache(
        File(context.cacheDir, "stream-cache"),
        LeastRecentlyUsedCacheEvictor(LIMIT_BYTES),
        StandaloneDatabaseProvider(context),
    ).also { instance = it }
}
