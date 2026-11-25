package com.example.myapplication.audiobooks.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Читает не больше [limit] байт; null — источник длиннее. Память не растёт вместе с размером файла. */
fun InputStream.readAtMost(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > limit) return null
        out.write(buffer, 0, read)
    }
}
