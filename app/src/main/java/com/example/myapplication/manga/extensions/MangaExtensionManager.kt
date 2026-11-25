package com.example.myapplication.manga.extensions

import com.example.myapplication.utils.runSuspendCatching
import android.app.Application
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.myapplication.data.local.AppLanguagePrefs
import com.example.myapplication.data.local.JsonMapFileStore
import com.example.myapplication.manga.domain.VetroMangaSource
import com.example.myapplication.network.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Поставленное расширение: запись на диске (APK лежит в `filesDir/manga_extensions`). */
@Serializable
data class InstalledExtension(
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val fileName: String,
    val iconUrl: String? = null,
    val languages: List<String> = emptyList(),
)

/** Расширение в памяти: его источники или причина, по которой оно не загрузилось. */
data class LoadedExtension(
    val info: InstalledExtension,
    val sources: List<VetroMangaSource>,
    val error: String? = null,
)

/**
 * Расширения Mihon внутри Vetro: каталог, установка в один тап, загрузка при старте.
 *
 * В систему ничего не ставится: APK скачивается в приватную папку приложения, проверяется подпись
 * репозитория, файл делается только для чтения и грузится своим ClassLoader поверх хоста
 * `eu.kanade.tachiyomi`. Источники расширений попадают в [com.example.myapplication.manga.source.MangaSourceEngine]
 * рядом со встроенными — выбор источника, ридер и загрузки глав работают с ними без изменений.
 */
class MangaExtensionManager(
    private val app: Application,
    private val http: OkHttpClient,
    private val settings: DataStore<Preferences>,
) {
    private val dir = File(app.filesDir, "manga_extensions")
    private val repo = MihonRepo.Keiyoushi
    private val store = JsonMapFileStore(File(dir, "installed_v1.json"), InstalledExtension.serializer(), TAG)
    private val catalog = ExtensionCatalog(http, File(dir, "catalog_v1.json"), repo)
    private val loadMutex = Mutex()
    private val installMutex = Mutex()

    @Volatile
    private var initialized = false

    private val _installed = MutableStateFlow<Map<String, LoadedExtension>>(emptyMap())

    /** Поставленные расширения по имени пакета. */
    val installed: StateFlow<Map<String, LoadedExtension>> = _installed.asStateFlow()

    /** Источники всех загруженных расширений (для движка поиска). Первый вызов грузит APK. */
    suspend fun sources(): List<VetroMangaSource> {
        ensureLoaded()
        return _installed.value.values.flatMap { it.sources }
    }

    suspend fun ensureLoaded() {
        if (initialized) return
        loadMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                store.ensureLoaded()
                if (store.value.isNotEmpty()) ExtensionRuntime.ensure(app)
                val languages = preferredLanguages()
                _installed.value = store.value.mapValues { (_, info) -> loadOne(info, languages) }
            }
            initialized = true
        }
    }

    suspend fun catalog(forceRefresh: Boolean = false): List<CatalogExtension> = catalog.load(forceRefresh)

    /** Рекомендованные для языка приложения, которых ещё нет (или они устарели). */
    suspend fun recommendedToInstall(all: List<CatalogExtension>): List<CatalogExtension> {
        val language = if (AppLanguagePrefs.current(settings) == AppLanguage.RU) "ru" else "en"
        val wanted = MihonRepo.Recommended[language].orEmpty()
        val byPackage = all.associateBy { it.packageName }
        return wanted.mapNotNull { byPackage[it] }.filter { hasUpdateOrMissing(it) }
    }

    fun hasUpdateOrMissing(extension: CatalogExtension): Boolean {
        val current = _installed.value[extension.packageName] ?: return true
        return extension.versionCode > current.info.versionCode
    }

    /** Ставит (или обновляет) расширение. Ошибка — в результате, а не исключением наружу. */
    suspend fun install(extension: CatalogExtension): Result<LoadedExtension> = installMutex.withLock {
        runSuspendCatching {
            withContext(Dispatchers.IO) {
                ensureLoaded()
                ExtensionRuntime.ensure(app)
                val file = download(extension)
                val info = InstalledExtension(
                    packageName = extension.packageName,
                    name = extension.name,
                    versionCode = extension.versionCode,
                    versionName = extension.versionName,
                    fileName = file.name,
                    iconUrl = extension.iconUrl,
                    languages = extension.languages,
                )
                val loaded = loadOne(info, preferredLanguages())
                loaded.error?.let { message ->
                    file.delete()
                    throw IOException(message)
                }
                val previous = store.value[extension.packageName]
                store.update { it + (extension.packageName to info) }
                _installed.update { it + (extension.packageName to loaded) }
                if (previous != null && previous.fileName != file.name) File(dir, previous.fileName).delete()
                loaded
            }
        }.onFailure { Log.w(TAG, "Install ${extension.packageName} failed", it) }
    }

    suspend fun uninstall(packageName: String) = installMutex.withLock {
        val info = store.value[packageName]
        store.update { it - packageName }
        _installed.update { it - packageName }
        if (info != null) withContext(Dispatchers.IO) { File(dir, info.fileName).delete() }
    }

    private fun download(extension: CatalogExtension): File {
        dir.mkdirs()
        val tmp = File(dir, "${extension.packageName}.download")
        tmp.delete()
        http.newCall(Request.Builder().url(extension.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
        }
        try {
            ExtensionRuntime.verify(app, tmp, extension.packageName, repo)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        val target = File(dir, "${extension.packageName}-${extension.versionCode}.apk")
        if (target.exists()) {
            target.setWritable(true)
            target.delete()
        }
        if (!tmp.renameTo(target)) throw IOException("Cannot move ${tmp.name}")
        // Android 14+ отказывается грузить код из файла, в который можно писать.
        target.setReadOnly()
        return target
    }

    private fun loadOne(info: InstalledExtension, languages: Set<String>): LoadedExtension = try {
        val file = File(dir, info.fileName)
        check(file.exists()) { "APK is missing" }
        val all = ExtensionRuntime.load(app, file)
        // Многоязычное расширение (MangaDex, MANGA Plus) отдаёт десятки источников - берём языки
        // пользователя; если ни одного такого нет, показываем все, раз уж расширение поставили.
        val chosen = all.filter { it.lang in languages }.ifEmpty { all }
        val multi = chosen.size > 1 && chosen.map { it.name }.distinct().size < chosen.size
        LoadedExtension(info, chosen.map { MihonSourceAdapter(it, showLanguage = multi) })
    } catch (e: Throwable) {
        // Throwable, а не Exception: несовместимое расширение падает LinkageError/NoClassDefFoundError.
        Log.w(TAG, "Load ${info.packageName} failed", e)
        LoadedExtension(info, emptyList(), e.message ?: e.javaClass.simpleName)
    }

    private suspend fun preferredLanguages(): Set<String> =
        if (AppLanguagePrefs.current(settings) == AppLanguage.RU) setOf("ru", "en", "all") else setOf("en", "all")

    private companion object {
        const val TAG = "MangaExtensions"
    }
}
