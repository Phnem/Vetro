package com.example.myapplication.media.source.vftmodule

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.libtorrent4j.TorrentHandle
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный локальный HTTP Range-сервер для потоковой передачи видео из торрентов в ExoPlayer.
 * Работает на 127.0.0.1, поддерживает перемотку (HTTP 206 Partial Content)
 * и последовательную приоритизацию кусков в libtorrent.
 */
class VftLocalStreamServer(
    private val torrentEngine: VftTorrentEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var port: Int = DEFAULT_PORT

    @Synchronized
    fun start(): Int {
        if (isRunning.get() && serverSocket?.isClosed == false) {
            return port
        }

        try {
            // Пробуем запустить на DEFAULT_PORT, если занят — ОС выделит свободный порт (0)
            val socket = runCatching {
                ServerSocket(DEFAULT_PORT, 50, InetAddress.getByName("127.0.0.1"))
            }.getOrElse {
                ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            }

            serverSocket = socket
            port = socket.localPort
            isRunning.set(true)

            scope.launch {
                listenLoop(socket)
            }

            Log.i(TAG, "VftLocalStreamServer started on 127.0.0.1:$port")
            return port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VftLocalStreamServer", e)
            return -1
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "VftLocalStreamServer stopped")
    }

    /**
     * Формирует локальный URL потока для ExoPlayer.
     */
    fun buildStreamUrl(infoHash: String, fileIndex: Int, fileName: String? = null): String {
        val currentPort = if (isRunning.get()) port else start()
        val safeName = fileName?.let { "/" + it.replace(Regex("""[^a-zA-Z0-9._-]"""), "_") }.orEmpty()
        return "http://127.0.0.1:$currentPort/stream/${infoHash.lowercase()}/$fileIndex$safeName"
    }

    /**
     * Формирует локальный URL с авто-определением файла (по сезону/серии) при первом запросе плеера.
     */
    fun buildAutoStreamUrl(infoHash: String, season: Int?, episode: Int?, fileName: String? = null): String {
        val currentPort = if (isRunning.get()) port else start()
        val safeName = fileName?.let { "/" + it.replace(Regex("""[^a-zA-Z0-9._-]"""), "_") }.orEmpty()
        val query = mutableListOf<String>()
        if (season != null) query.add("season=$season")
        if (episode != null) query.add("episode=$episode")
        val queryString = if (query.isNotEmpty()) "?" + query.joinToString("&") else ""
        return "http://127.0.0.1:$currentPort/stream/${infoHash.lowercase()}/auto$safeName$queryString"
    }

    private suspend fun listenLoop(server: ServerSocket) {
        while (isRunning.get() && !server.isClosed && scope.isActive) {
            try {
                val clientSocket = server.accept()
                scope.launch {
                    handleClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (!isRunning.get()) break
            } catch (e: Exception) {
                Log.w(TAG, "Error accepting client connection", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = BufferedOutputStream(s.getOutputStream())

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendError(out, 400, "Bad Request")
                return
            }

            val path = parts[1]
            val pathWithoutQuery = path.substringBefore('?')
            val queryString = path.substringAfter('?', "")
            val queryParams = queryString.split('&').filter { it.contains('=') }.associate {
                val p = it.split('=', limit = 2)
                p[0] to p[1]
            }

            // Ожидаемый путь: /stream/<hash>/<fileIndex|auto>[/name]
            val pathMatcher = Regex("""^/stream/([a-fA-F0-9]{40}|rutracker_\d+|[a-zA-Z0-9]+)/(\d+|auto)""").find(pathWithoutQuery)
            if (pathMatcher == null) {
                sendError(out, 404, "Not Found")
                return
            }

            val hash = pathMatcher.groupValues[1].lowercase()
            val fileIndexParam = pathMatcher.groupValues[2]

            // Читаем заголовки
            var rangeHeader: String? = null
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            val info = torrentEngine.getCachedInfo(hash) ?: kotlinx.coroutines.runBlocking {
                runCatching { torrentEngine.fetchMetadata("magnet:?xt=urn:btih:$hash") }.getOrNull()
            }

            if (info == null) {
                sendError(out, 404, "Torrent metadata not loaded")
                return
            }

            val fileIndex = if (fileIndexParam == "auto") {
                val s = queryParams["season"]?.toIntOrNull()
                val ep = queryParams["episode"]?.toIntOrNull()
                val allFiles = torrentEngine.listFiles(info)
                val target = torrentEngine.findTargetFile(allFiles, s, ep)
                    ?: allFiles.firstOrNull { it.isVideo }
                    ?: run {
                        sendError(out, 404, "No video files found in torrent")
                        return
                    }
                target.index
            } else {
                fileIndexParam.toIntOrNull() ?: run {
                    sendError(out, 400, "Invalid file index")
                    return
                }
            }

            if (fileIndex < 0 || fileIndex >= info.numFiles()) {
                sendError(out, 404, "File index out of bounds")
                return
            }

            val files = info.files()
            val totalSize = files.fileSize(fileIndex)
            val fileOffset = files.fileOffset(fileIndex)
            val pieceLength = info.pieceLength()
            val fileName = files.fileName(fileIndex)
            val mimeType = mimeForVideo(fileName)

            val handle = torrentEngine.startStreaming(info, fileIndex)
            val diskFile = torrentEngine.fileOnDisk(handle, fileIndex)

            // Разбор Range (bytes=start-end)
            var rangeStart = 0L
            var rangeEnd = totalSize - 1
            var isPartial = false

            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val rangeVal = rangeHeader.removePrefix("bytes=").trim()
                val dashIdx = rangeVal.indexOf('-')
                if (dashIdx >= 0) {
                    val sStr = rangeVal.substring(0, dashIdx).trim()
                    val eStr = rangeVal.substring(dashIdx + 1).trim()
                    if (sStr.isNotEmpty()) rangeStart = sStr.toLongOrNull() ?: 0L
                    if (eStr.isNotEmpty()) rangeEnd = eStr.toLongOrNull() ?: (totalSize - 1)
                    isPartial = true
                }
            }

            if (rangeStart > rangeEnd || rangeStart >= totalSize) {
                sendRangeNotSatisfiable(out, totalSize)
                return
            }

            val contentLength = rangeEnd - rangeStart + 1

            // Отправка HTTP-заголовков
            if (isPartial) {
                out.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                out.write("Content-Range: bytes $rangeStart-$rangeEnd/$totalSize\r\n".toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            }

            out.write("Content-Type: $mimeType\r\n".toByteArray())
            out.write("Content-Length: $contentLength\r\n".toByteArray())
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Connection: keep-alive\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.flush()

            // Передача байтов с ожиданием кусков (awaitPiece)
            streamFileRange(
                handle = handle,
                diskFile = diskFile,
                fileOffset = fileOffset,
                pieceLength = pieceLength,
                rangeStart = rangeStart,
                contentLength = contentLength,
                out = out,
            )
        }
    }

    private fun streamFileRange(
        handle: TorrentHandle,
        diskFile: File,
        fileOffset: Long,
        pieceLength: Int,
        rangeStart: Long,
        contentLength: Long,
        out: BufferedOutputStream,
    ) {
        var currentPos = rangeStart
        var remaining = contentLength
        val buffer = ByteArray(BUFFER_SIZE)
        var raf: RandomAccessFile? = null

        try {
            while (remaining > 0) {
                val absolute = fileOffset + currentPos
                val piece = (absolute / pieceLength).toInt()

                // Ожидаем готовности куска
                awaitPiece(handle, piece)

                val pieceEnd = (piece + 1).toLong() * pieceLength
                val bytesToReadFromPiece = minOf(remaining, pieceEnd - absolute, BUFFER_SIZE.toLong()).toInt()

                if (raf == null) {
                    if (!diskFile.exists()) {
                        diskFile.parentFile?.mkdirs()
                    }
                    raf = RandomAccessFile(diskFile, "r")
                }

                raf.seek(currentPos)
                val read = raf.read(buffer, 0, bytesToReadFromPiece)
                if (read < 0) break

                out.write(buffer, 0, read)
                out.flush()

                currentPos += read
                remaining -= read
            }
        } catch (e: SocketException) {
            // Клиент закрыл соединение или перемотал плеер — нормальная ситуация
        } catch (e: Exception) {
            Log.w(TAG, "Streaming interrupted: ${e.message}")
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun awaitPiece(handle: TorrentHandle, piece: Int) {
        if (handle.havePiece(piece)) return

        val lastPiece = handle.torrentFile().numPieces() - 1
        for (k in 0..READ_AHEAD_PIECES) {
            val p = piece + k
            if (p > lastPiece) break
            if (!handle.havePiece(p)) {
                handle.setPieceDeadline(p, k * DEADLINE_STEP_MS)
            }
        }

        val deadline = System.currentTimeMillis() + STALL_TIMEOUT_MS
        while (!handle.havePiece(piece)) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException("Torrent piece $piece download timed out")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for piece $piece", e)
            }
        }
    }

    private fun sendError(out: BufferedOutputStream, code: Int, message: String) {
        val body = "Error $code: $message"
        val resp = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        runCatching {
            out.write(resp.toByteArray())
            out.flush()
        }
    }

    private fun sendRangeNotSatisfiable(out: BufferedOutputStream, totalSize: Long) {
        val resp = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalSize\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(resp.toByteArray())
            out.flush()
        }
    }

    private fun mimeForVideo(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    companion object {
        private const val TAG = "VftLocalStreamServer"
        private const val DEFAULT_PORT = 18092
        private const val BUFFER_SIZE = 32 * 1024
        private const val READ_AHEAD_PIECES = 8
        private const val DEADLINE_STEP_MS = 300
        private const val POLL_MS = 50L
        private const val STALL_TIMEOUT_MS = 45_000L
    }
}
