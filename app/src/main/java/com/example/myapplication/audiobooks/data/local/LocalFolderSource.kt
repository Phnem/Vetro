package com.example.myapplication.audiobooks.data.local

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter as MediaChapter
import androidx.media3.inspector.MetadataRetriever
import com.example.myapplication.audiobooks.domain.model.AudioTrack
import com.example.myapplication.audiobooks.domain.model.Chapter
import com.example.myapplication.audiobooks.domain.model.MediaManifest
import com.example.myapplication.audiobooks.domain.model.NarrationId
import com.example.myapplication.audiobooks.domain.model.VariantId
import com.example.myapplication.audiobooks.domain.model.WorkId
import com.example.myapplication.audiobooks.domain.source.ManifestSource
import com.example.myapplication.audiobooks.domain.source.NaturalAudioOrder
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalBook(
    val variant: VariantId,
    val workId: WorkId,
    val narrationId: NarrationId,
    val title: String,
    val folderUri: Uri,
    val fileCount: Int,
    val artworkUri: Uri? = null,
)

/** SAF-backed books. Only the tree grant is persisted; playable file URIs stay in manifests. */
@UnstableApi
class LocalFolderSource(private val context: Context) : ManifestSource {
    private val prefs = context.getSharedPreferences("audiobook_local_folders", Context.MODE_PRIVATE)

    suspend fun addTree(treeUri: Uri): List<LocalBook> = withContext(Dispatchers.IO) {
        require(AudiobookFolderGuard.isAllowed(treeUri)) { "Choose a dedicated audiobook folder" }
        if (treeUri.scheme == "content") {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val books = scanTree(treeUri)
        val roots = prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty() + treeUri.toString()
        prefs.edit().putStringSet(KEY_ROOTS, roots).apply()
        books
    }

    suspend fun books(): List<LocalBook> = withContext(Dispatchers.IO) {
        prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty().flatMap { uri ->
            runCatching { scanTree(Uri.parse(uri)) }.getOrDefault(emptyList())
        }.sortedWith(compareBy(NaturalAudioOrder) { it.title })
    }

    suspend fun unavailableFolderCount(): Int = withContext(Dispatchers.IO) {
        prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty().count { stored ->
            val uri = Uri.parse(stored)
            !hasGrant(uri) || runCatching { documentTree(uri)?.exists() }.getOrNull() != true
        }
    }

    suspend fun removeTree(treeUri: Uri) = withContext(Dispatchers.IO) {
        val roots = prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty() - treeUri.toString()
        prefs.edit().putStringSet(KEY_ROOTS, roots).apply()
        if (treeUri.scheme == "content") {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    override fun supports(variant: VariantId): Boolean = variant.value.startsWith(PREFIX)

    override suspend fun refresh(variant: VariantId): MediaManifest = withContext(Dispatchers.IO) {
        val (treeUri, bookUri) = decode(variant) ?: throw FileNotFoundException("Invalid local book identity")
        if (treeUri.toString() !in prefs.getStringSet(KEY_ROOTS, emptySet()).orEmpty()) {
            throw SecurityException("Audiobook folder was not selected")
        }
        if (!hasGrant(treeUri)) throw SecurityException("Audiobook folder permission was revoked")
        val root = documentTree(treeUri) ?: throw FileNotFoundException("Audiobook folder was removed")
        val book = if (root.uri == bookUri) root else
            root.listFiles().firstOrNull { it.isDirectory && it.uri == bookUri }
                ?: throw FileNotFoundException("Audiobook was moved or removed")
        val files = audioFiles(book)
        if (files.isEmpty()) throw FileNotFoundException("Audiobook has no audio files")

        val tracks = ArrayList<AudioTrack>(files.size)
        val chapters = ArrayList<Chapter>()
        var globalStart: Long? = 0L
        for ((index, file) in files.withIndex()) {
            val metadata = readAudioMetadata(file.uri, file.name.orEmpty())
            tracks += AudioTrack(
                index = index,
                url = file.uri.toString(),
                mimeType = file.type,
                durationMs = metadata.durationMs,
                sizeBytes = file.length().takeIf { it > 0 },
            )
            val start = globalStart
            if (start != null) {
                val embedded = metadata.chapters.sortedBy { it.startMs }
                if (embedded.isEmpty()) {
                    chapters += Chapter(chapters.size, metadata.title ?: file.name.orEmpty().substringBeforeLast('.'), start, metadata.durationMs)
                } else {
                    for (chapter in embedded) {
                        chapters += Chapter(chapters.size, chapter.title, start + chapter.startMs, chapter.durationMs)
                    }
                }
            }
            globalStart = if (start != null && metadata.durationMs != null) start + metadata.durationMs else null
        }
        MediaManifest(variant, tracks, chapters, System.currentTimeMillis(), null)
    }

    /**
     * CUE-лист в папке книги (разметка глав единственного файла), текстом. Старые русские листы —
     * в cp1251: если UTF-8 не читается, пробуем её.
     */
    suspend fun cueSheet(variant: VariantId): String? = withContext(Dispatchers.IO) {
        val (treeUri, bookUri) = decode(variant) ?: return@withContext null
        if (!hasGrant(treeUri)) return@withContext null
        val root = documentTree(treeUri) ?: return@withContext null
        val book = if (root.uri == bookUri) root else
            runCatching { root.listFiles().firstOrNull { it.isDirectory && it.uri == bookUri } }.getOrNull() ?: return@withContext null
        val cue = runCatching { book.listFiles().singleOrNull { it.isFile && it.name.orEmpty().endsWith(".cue", ignoreCase = true) } }
            .getOrNull() ?: return@withContext null
        val bytes = runCatching { context.contentResolver.openInputStream(cue.uri)?.use { it.readBytes() } }.getOrNull()
            ?.takeIf { it.size in 1..MAX_CUE_BYTES } ?: return@withContext null
        val utf8 = String(bytes, Charsets.UTF_8)
        if ('�' in utf8) String(bytes, charset("windows-1251")) else utf8
    }

    /** Текст книги рядом со звуком (EPUB/FB2/TXT в папке книги или в её подпапке): имя и адрес. */
    suspend fun textFiles(variant: VariantId): List<Pair<String, Uri>> = withContext(Dispatchers.IO) {
        val (treeUri, bookUri) = decode(variant) ?: return@withContext emptyList()
        if (!hasGrant(treeUri)) return@withContext emptyList()
        val root = documentTree(treeUri) ?: return@withContext emptyList()
        val book = if (root.uri == bookUri) root else
            runCatching { root.listFiles().firstOrNull { it.isDirectory && it.uri == bookUri } }.getOrNull() ?: return@withContext emptyList()
        val found = ArrayList<Pair<String, Uri>>()
        fun collect(dir: DocumentFile, depth: Int) {
            for (child in runCatching { dir.listFiles() }.getOrDefault(emptyArray())) {
                val name = child.name ?: continue
                if (child.isFile && name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS) found += name to child.uri
                else if (child.isDirectory && depth < MAX_DEPTH) collect(child, depth + 1)
            }
        }
        collect(book, 0)
        found
    }

    private fun scanTree(treeUri: Uri): List<LocalBook> {
        if (!hasGrant(treeUri)) return emptyList()
        val root = documentTree(treeUri) ?: return emptyList()
        val children = runCatching { root.listFiles().toList() }.getOrDefault(emptyList())
        val books = ArrayList<LocalBook>()
        val rootFiles = audioFiles(root, includeDirectories = false)
        if (rootFiles.isNotEmpty()) {
            books += localBook(treeUri, root, rootFiles)
        }
        for (folder in children.asSequence().filter { it.isDirectory }.take(MAX_BOOKS)) {
            val files = audioFiles(folder)
            if (files.isNotEmpty()) {
                books += localBook(treeUri, folder, files)
            }
        }
        return books
    }

    private fun localBook(treeUri: Uri, folder: DocumentFile, files: List<DocumentFile>): LocalBook {
        val variant = encode(treeUri, folder.uri)
        fun stableId(kind: String): String {
            val key = "$kind:${variant.value}"
            return prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(key, it).commit()
            }
        }
        return LocalBook(
            variant = variant,
            workId = WorkId(stableId("work")),
            narrationId = NarrationId(stableId("narration")),
            title = folder.name ?: "Audiobook",
            folderUri = folder.uri,
            fileCount = files.size,
            artworkUri = findArtwork(folder, files),
        )
    }

    /** A local book owns its artwork: prefer an image beside the tracks, then embedded audio art. */
    private fun findArtwork(folder: DocumentFile, tracks: List<DocumentFile>): Uri? {
        val images = runCatching { folder.listFiles().filter { file ->
            file.isFile && file.name.orEmpty().substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        } }.getOrDefault(emptyList())
        val preferred = images.minByOrNull { image ->
            val name = image.name.orEmpty().substringBeforeLast('.').lowercase()
            COVER_NAMES.indexOf(name).takeIf { it >= 0 } ?: COVER_NAMES.size
        }?.takeIf { image -> image.name.orEmpty().substringBeforeLast('.').lowercase() in COVER_NAMES }
        val chosen = preferred ?: images.singleOrNull()
        if (chosen != null) return chosen.uri

        val key = tracks.take(3).joinToString("|") { "${it.uri}:${it.length()}:${it.lastModified()}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val directory = File(context.filesDir, "audiobook_covers").apply { mkdirs() }
        val cached = File(directory, "$digest.img")
        if (cached.isFile && cached.length() > 0) return Uri.fromFile(cached)
        val picture = tracks.take(3).firstNotNullOfOrNull { track ->
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, track.uri)
                    retriever.embeddedPicture
                }
            }.getOrNull()?.takeIf { it.size in 1..MAX_EMBEDDED_ART_BYTES }
        } ?: return null
        return runCatching {
            cached.outputStream().use { it.write(picture) }
            Uri.fromFile(cached)
        }.getOrNull()
    }

    private fun audioFiles(folder: DocumentFile, includeDirectories: Boolean = true): List<DocumentFile> {
        val files = ArrayList<Pair<String, DocumentFile>>()
        fun collect(dir: DocumentFile, depth: Int, path: String) {
            if (files.size >= MAX_TRACKS) return
            val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
            for (child in children) {
                if (files.size >= MAX_TRACKS) break
                val name = child.name ?: continue
                if (child.isFile && name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    files += "$path$name" to child
                } else if (includeDirectories && child.isDirectory && depth < MAX_DEPTH) {
                    collect(child, depth + 1, "$path$name/")
                }
            }
        }
        collect(folder, 0, "")
        return files.sortedWith { a, b -> NaturalAudioOrder.compare(a.first, b.first) }.map { it.second }
    }

    private fun hasGrant(treeUri: Uri): Boolean = treeUri.scheme == "file" ||
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    private fun documentTree(uri: Uri): DocumentFile? = if (uri.scheme == "file") {
        uri.path?.let { DocumentFile.fromFile(File(it)) }
    } else DocumentFile.fromTreeUri(context, uri)

    /**
     * Автор по тегам первого файла книги (исполнитель альбома → исполнитель → автор). У папки
     * своего автора нет, а без него системная карточка медиа показывает одну главу.
     */
    fun author(manifest: MediaManifest): String? {
        val uri = manifest.tracks.firstOrNull()?.url?.let(Uri::parse) ?: return null
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                listOf(
                    MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                    MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                ).firstNotNullOfOrNull { key -> retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() } }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    /**
     * Теги, разложенные по ролям. «Исполнитель» у аудиокниг почти всегда чтец, а не автор, поэтому
     * автором считается только явный тег (AUTHOR/WRITER), а исполнители отдаются отдельно как
     * [LocalTags.people]: кто из них кто — решает совпадение с каталогом, а не порядок тегов.
     */
    fun tags(manifest: MediaManifest): LocalTags {
        val uri = manifest.tracks.firstOrNull()?.url?.let(Uri::parse) ?: return LocalTags(null, emptyList())
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
                LocalTags(
                    author = tag(MediaMetadataRetriever.METADATA_KEY_AUTHOR) ?: tag(MediaMetadataRetriever.METADATA_KEY_WRITER),
                    people = listOfNotNull(
                        tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                    ).distinct(),
                )
            } finally {
                retriever.release()
            }
        }.getOrDefault(LocalTags(null, emptyList()))
    }

    private fun readAudioMetadata(uri: Uri, name: String): AudioMetadata {
        var durationMs: Long? = null
        var title: String? = null
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0 }
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            } finally {
                retriever.release()
            }
        }
        val embedded = if (name.substringAfterLast('.', "").lowercase() in CHAPTER_FORMATS) {
            runCatching { inspectChapters(uri) }.getOrDefault(emptyList())
        } else emptyList()
        return AudioMetadata(durationMs, title, embedded)
    }

    private fun inspectChapters(uri: Uri): List<EmbeddedChapter> {
        MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
            val groups = retriever.retrieveTrackGroups().get(INSPECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val found = ArrayList<EmbeddedChapter>()
            for (groupIndex in 0 until groups.length) {
                val group = groups[groupIndex]
                for (formatIndex in 0 until group.length) {
                    val entries = group.getFormat(formatIndex).metadata?.getEntriesOfType(MediaChapter::class.java).orEmpty()
                    for (entry in entries) {
                        val start = entry.startTimeMs
                        if (start == C.TIME_UNSET || start < 0) continue
                        val end = entry.endTimeMs.takeIf { it != C.TIME_UNSET && it >= start }
                        found += EmbeddedChapter(entry.title?.value?.takeIf { it.isNotBlank() } ?: "Chapter ${found.size + 1}", start, end?.minus(start))
                    }
                }
            }
            return found.distinctBy { it.startMs }.sortedBy { it.startMs }
        }
    }

    private fun encode(treeUri: Uri, bookUri: Uri): VariantId =
        VariantId(PREFIX + encodePart(treeUri.toString()) + "." + encodePart(bookUri.toString()))

    private fun decode(variant: VariantId): Pair<Uri, Uri>? = runCatching {
        require(supports(variant))
        val parts = variant.value.removePrefix(PREFIX).split('.', limit = 2)
        require(parts.size == 2)
        Uri.parse(decodePart(parts[0])) to Uri.parse(decodePart(parts[1]))
    }.getOrNull()

    private fun encodePart(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodePart(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private data class AudioMetadata(val durationMs: Long?, val title: String?, val chapters: List<EmbeddedChapter>)
    private data class EmbeddedChapter(val title: String, val startMs: Long, val durationMs: Long?)

    internal companion object {
        const val PREFIX = "local:"
        const val KEY_ROOTS = "tree_uris"
        const val MAX_BOOKS = 100
        const val MAX_TRACKS = 200
        const val MAX_DEPTH = 1
        const val INSPECT_TIMEOUT_SECONDS = 15L
        val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav")
        val CHAPTER_FORMATS = setOf("mp3", "m4b", "m4a")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val COVER_NAMES = listOf("cover", "folder", "front", "artwork")
        const val MAX_EMBEDDED_ART_BYTES = 8 * 1024 * 1024
        const val MAX_CUE_BYTES = 512 * 1024
        val TEXT_EXTENSIONS = setOf("epub", "fb2", "txt")
    }
}

/**
 * Теги первого файла книги по ролям. [author] — только явный тег автора; [people] — исполнители и
 * композиторы, чья роль из тегов неизвестна (у аудиокниг это, как правило, чтец).
 */
data class LocalTags(val author: String?, val people: List<String>)
