package com.example.myapplication.manga.extensions

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.io.File
import java.security.MessageDigest

/**
 * Среда, в которой живут расширения: Injekt с теми объектами, что расширения запрашивают у хоста
 * (`Application`, `NetworkHelper`, `Json`, JS-движок), проверка подписи APK и загрузка классов.
 */
internal object ExtensionRuntime {
    @Volatile
    private var ready = false

    /** Регистрирует хост-объекты в Injekt один раз за процесс; до этого ни одно расширение не грузим. */
    fun ensure(app: Application) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            Injekt.addSingleton(fullType<Application>(), app)
            Injekt.addSingletonFactory(fullType<NetworkHelper>()) { NetworkHelper(app) }
            Injekt.addSingletonFactory(fullType<Json>()) {
                Json { ignoreUnknownKeys = true; explicitNulls = false }
            }
            Injekt.addFactory(fullType<JavaScriptEngine>()) { JavaScriptEngine(app) }
            ready = true
        }
    }

    /**
     * Проверяет скачанный APK до того, как из него загрузится хоть один класс: это расширение
     * Mihon с ожидаемым пакетом, нужной версией API и подписью репозитория.
     */
    fun verify(app: Application, apk: File, expectedPackage: String, repo: MihonRepo) {
        val info = archiveInfo(app, apk, signatures = true) ?: error("Not an APK")
        check(info.packageName == expectedPackage) { "Package mismatch: ${info.packageName}" }
        val lib = info.applicationInfo?.metaData?.get(META_LIB)?.toString()
        check(lib in MihonRepo.SupportedLibs) { "Unsupported extension API $lib" }
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        val fingerprints = certs.map { sha256Hex(it) }
        check(fingerprints.isNotEmpty() && fingerprints.all { it.equals(repo.signingFingerprint, ignoreCase = true) }) {
            "Signature does not match ${repo.name}"
        }
    }

    /** Источники каталога из APK. Файл должен быть только для чтения (требование Android 14+). */
    fun load(app: Application, apk: File): List<CatalogueSource> {
        val info = archiveInfo(app, apk, signatures = false) ?: error("Not an APK")
        val classes = info.applicationInfo?.metaData?.getString(META_CLASS)
            ?: error("No extension class in manifest")
        val loader = PathClassLoader(apk.absolutePath, null, app.classLoader)
        return classes.split(';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { name ->
            val fullName = if (name.startsWith('.')) info.packageName + name else name
            when (val entry = Class.forName(fullName, false, loader).getDeclaredConstructor().newInstance()) {
                is SourceFactory -> entry.createSources()
                is Source -> listOf(entry)
                else -> error("Unknown entry type ${entry.javaClass.name}")
            }
        }.filterIsInstance<CatalogueSource>()
    }

    private fun archiveInfo(app: Application, apk: File, signatures: Boolean): PackageInfo? {
        var flags = PackageManager.GET_META_DATA
        if (signatures) {
            flags = flags or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
        }
        return app.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val META_CLASS = "tachiyomi.extension.class"
    private const val META_LIB = "tachiyomix.extensionLib"
}
