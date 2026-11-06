package eu.kanade.tachiyomi

import com.phnem.vetro.BuildConfig

/** Версия приложения-хоста: расширения пишут её в User-Agent и в отчёты об ошибках. */
object AppInfo {
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersionName(): String = BuildConfig.VERSION_NAME
}
