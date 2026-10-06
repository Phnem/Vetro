package com.example.myapplication.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Скачанный файл не совпал с тем, что заявлено в релизе (размер или SHA-256). */
class UpdateIntegrityException(message: String) : IOException(message)

/**
 * Скачивает APK обновления. Заменяет системный `DownloadManager`, который жил внутри экрана настроек
 * и зависел от его жизни: тут загрузка идёт в приложении, прогресс считается сам, оборванное
 * докачивается с того места, где остановились (HTTP Range), а готовый файл проверяется по размеру и
 * контрольной сумме до того, как его покажут пользователю.
 *
 * Файл появляется под своим именем только целым и проверенным: до этого он лежит как `.part`.
 */
class UpdateDownloader(
    private val client: OkHttpClient,
    private val dir: File,
    private val bufferSize: Int = 64 * 1024,
) {

    /** Куда ложится готовый APK этого релиза. */
    fun fileFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk")

    private fun partFor(tag: String): File = File(dir, "vetro-${safe(tag)}.apk.part")

    /** Скачан ли уже этот релиз целиком (файл появляется под своим именем только после проверки). */
    fun downloaded(tag: String): File? = fileFor(tag).takeIf { it.isFile && it.length() > 0 }

    /** Убрать всё, кроме файла [keepTag]: старые версии и недокачанное не должны копиться. */
    fun cleanExcept(keepTag: String?) {
        val keep = keepTag?.let { fileFor(it).name }
        dir.listFiles()?.forEach { file -> if (file.name != keep) file.delete() }
    }

    /**
     * @param onProgress вызывается по ходу загрузки: сколько байт уже есть и сколько всего; чаще
     *  чем раз в [PROGRESS_STEP_BYTES] не зовётся.
     * @throws UpdateIntegrityException если скачанное не совпало с релизом; недокачанный файл при этом удалён.
     */
    suspend fun download(release: UpdateRelease, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            downloaded(release.tag)?.let { return@withContext it }
            val part = partFor(release.tag)
            var have = if (part.isFile) part.length() else 0L
            // Недокачанный файл больше заявленного размера - мусор от прежней попытки.
            if (release.sizeBytes > 0 && have > release.sizeBytes) {
                part.delete()
                have = 0L
            }

            val request = Request.Builder().url(release.downloadUrl)
                .header("Accept", "application/octet-stream")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416 && have > 0) {
                    // Сервер говорит, что докачивать нечего или файл другой: начинаем заново.
                    part.delete()
                    throw IOException("range not satisfiable")
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val resumed = response.code == 206
                if (!resumed) have = 0L
                val total = when {
                    release.sizeBytes > 0 -> release.sizeBytes
                    body.contentLength() > 0 -> have + body.contentLength()
                    else -> 0L
                }
                RandomAccessFile(part, "rw").use { out ->
                    if (resumed) out.seek(have) else out.setLength(0)
                    val buffer = ByteArray(bufferSize)
                    var done = have
                    var reported = done
                    onProgress(done, total)
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (done - reported >= PROGRESS_STEP_BYTES) {
                                reported = done
                                onProgress(done, total)
                            }
                        }
                    }
                    onProgress(done, total)
                }
            }
            verify(part, release)
            val target = fileFor(release.tag)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("cannot move the downloaded file")
            }
            target
        }

    private fun verify(file: File, release: UpdateRelease) {
        if (release.sizeBytes > 0 && file.length() != release.sizeBytes) {
            file.delete()
            throw UpdateIntegrityException("size ${file.length()} != ${release.sizeBytes}")
        }
        val expected = release.sha256 ?: return
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw UpdateIntegrityException("sha256 mismatch")
        }
    }

    companion object {
        const val PROGRESS_STEP_BYTES = 128L * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Тег идёт в имя файла: всё, что не буква, цифра, точка и дефис, заменяется. */
        private fun safe(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
