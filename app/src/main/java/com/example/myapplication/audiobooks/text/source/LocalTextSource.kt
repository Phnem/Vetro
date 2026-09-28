package com.example.myapplication.audiobooks.text.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.example.myapplication.audiobooks.data.local.LocalFolderSource
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.text.BookText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Свой текст пользователя: файл, выбранный для этой озвучки, или EPUB/FB2/TXT в папке своей книги.
 * Он точнее любого найденного в сети — это тот текст, который пользователь сам считает книгой.
 */
@UnstableApi
class LocalTextSource(
    private val context: Context,
    private val local: LocalFolderSource,
) : BookTextSource {
    override val id = "local"
    override val displayName = "Свой файл"

    private val prefs = context.getSharedPreferences("audiobook_text_files", Context.MODE_PRIVATE)

    /** Файл, выбранный пользователем для озвучки: запоминается и читается и после перезапуска. */
    fun choose(variant: VariantId, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putString(variant.value, uri.toString()).apply()
    }

    fun chosen(variant: VariantId): Uri? = prefs.getString(variant.value, null)?.let(Uri::parse)

    fun forget(variant: VariantId) = prefs.edit().remove(variant.value).apply()

    override suspend fun find(query: TextQuery): List<TextCandidate> {
        val out = ArrayList<TextCandidate>()
        chosen(query.variant)?.let { out += TextCandidate(id, it.toString(), query.title, query.authors, query.language, exact = true) }
        if (local.supports(query.variant)) {
            local.textFiles(query.variant).forEach { (name, uri) ->
                out += TextCandidate(id, uri.toString(), name.substringBeforeLast('.'), query.authors, null, exact = true)
            }
        }
        return out
    }

    override suspend fun load(candidate: TextCandidate): BookText? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(candidate.id)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > TextParsers.MAX_BYTES) return@withContext null
            }
            out.toByteArray()
        } ?: return@withContext null
        val name = uri.lastPathSegment.orEmpty()
        TextParsers.parse(bytes, name, candidate.language)
    }
}
