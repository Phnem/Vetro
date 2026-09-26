package com.example.myapplication.audiobooks.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.RandomAccessFile
import org.libtorrent4j.TorrentHandle

/**
 * Звук из раздачи прямо во время загрузки: адрес трека `vetro-torrent://<хеш>/<индекс файла>`.
 * Чтение просит у движка нужный кусок и следующие за ним с дедлайном и ждёт, пока они придут;
 * раздача молчит дольше [STALL_MS] — ошибка чтения, и плеер переходит к следующему звену цепочки.
 */
@UnstableApi
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var handle: TorrentHandle? = null
    private var file: RandomAccessFile? = null
    private var fileIndex = 0
    private var fileOffset = 0L
    private var pieceLength = 0
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        val hash = u.host ?: throw IOException("torrent uri without hash")
        fileIndex = u.lastPathSegment?.toIntOrNull() ?: throw IOException("torrent uri without file")
        transferInitializing(dataSpec)
        val info = engine.cached(hash) ?: throw IOException("torrent metadata is not cached")
        val files = info.files()
        val audio = (0 until info.numFiles()).filter { TorrentFiles.isAudio(files.fileName(it)) }.toSet() + fileIndex
        val h = engine.ensure(info, audio)
        engine.focus(h, fileIndex)
        handle = h
        fileOffset = files.fileOffset(fileIndex)
        pieceLength = info.pieceLength()
        val size = files.fileSize(fileIndex)
        position = dataSpec.position
        if (position > size) throw IOException("position beyond file")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val h = handle ?: throw IOException("not opened")
        val absolute = fileOffset + position
        val piece = (absolute / pieceLength).toInt()
        awaitPiece(h, piece)
        val pieceEnd = (piece + 1).toLong() * pieceLength
        val n = minOf(length.toLong(), remaining, pieceEnd - absolute).toInt()
        val raf = file ?: RandomAccessFile(engine.fileOnDisk(h, fileIndex), "r").also { file = it }
        raf.seek(position)
        val read = raf.read(buffer, offset, n)
        if (read < 0) throw IOException("torrent file shorter than expected")
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    /** Кусок нужен сейчас, несколько следующих — чуть позже; ждём, пока придёт. */
    private fun awaitPiece(h: TorrentHandle, piece: Int) {
        if (h.havePiece(piece)) return
        val last = h.torrentFile().numPieces() - 1
        for (k in 0..READ_AHEAD_PIECES) {
            val p = piece + k
            if (p > last) break
            if (!h.havePiece(p)) h.setPieceDeadline(p, k * DEADLINE_STEP_MS)
        }
        val deadline = System.currentTimeMillis() + STALL_MS
        while (!h.havePiece(piece)) {
            if (System.currentTimeMillis() > deadline) throw IOException("torrent stalled on piece $piece")
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted", e)
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { file?.close() }
        file = null
        if (handle != null) {
            handle = null
            transferEnded()
        }
    }

    class Factory(private val engine: TorrentEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }

    private companion object {
        const val READ_AHEAD_PIECES = 8
        const val DEADLINE_STEP_MS = 400
        const val POLL_MS = 50L
        const val STALL_MS = 60_000L
    }
}

/** Какие файлы раздачи — звук, и в каком порядке их слушать. */
object TorrentFiles {
    private val AUDIO = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav")

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO

    fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4b", "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> null
    }
}
