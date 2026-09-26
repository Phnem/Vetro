package com.example.myapplication.audiobooks.torrent

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Треки из раздачи (`vetro-torrent://`) — торрент-движку, всё остальное — обычному источнику. */
@UnstableApi
class SchemeRoutingDataSource(
    private val regular: DataSource,
    private val torrent: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        regular.addTransferListener(transferListener)
        torrent.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme == SCHEME) torrent else regular
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "not opened" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val regular: DataSource.Factory, private val torrent: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SchemeRoutingDataSource(regular.createDataSource(), torrent.createDataSource())
    }

    companion object {
        const val SCHEME = "vetro-torrent"

        fun trackUri(hash: String, fileIndex: Int): String = "$SCHEME://${hash.lowercase()}/$fileIndex"
    }
}
