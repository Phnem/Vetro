package com.example.myapplication.media.remote.proxy

/**
 * Текстовые субтитры между SRT и WebVTT без изменения содержания: разные приёмники понимают
 * разное (Cast — WebVTT, Samsung по DLNA — SRT). ASS/SSA не конвертируются — там оформление.
 */
object SubtitleFormats {
    private val SRT_TIME = Regex("""(\d{1,2}:\d{2}:\d{2}),(\d{3})""")
    private val VTT_TIME = Regex("""(\d{1,2}:)?(\d{2}:\d{2})\.(\d{3})""")

    fun isVtt(text: String): Boolean = text.trimStart(Char(0xFEFF)).startsWith("WEBVTT")

    fun srtToVtt(srt: String): String {
        val body = srt.trimStart(Char(0xFEFF)).replace("\r\n", "\n")
        val converted = body.lineSequence().joinToString("\n") { line ->
            if ("-->" in line) SRT_TIME.replace(line) { "${it.groupValues[1]}.${it.groupValues[2]}" } else line
        }
        return "WEBVTT\n\n" + converted.trim() + "\n"
    }

    fun vttToSrt(vtt: String): String {
        val lines = vtt.trimStart(Char(0xFEFF)).replace("\r\n", "\n").lines()
        val out = StringBuilder()
        var index = 1
        var i = 0
        // Пропускаем заголовок и блоки NOTE/STYLE до первой реплики.
        while (i < lines.size && "-->" !in lines[i]) i++
        while (i < lines.size) {
            val line = lines[i]
            if ("-->" in line) {
                val parts = line.split("-->")
                val start = srtTime(parts[0].trim())
                val end = srtTime(parts[1].trim().substringBefore(' '))
                out.append(index++).append('\n').append(start).append(" --> ").append(end).append('\n')
                i++
                while (i < lines.size && lines[i].isNotBlank()) { out.append(lines[i]).append('\n'); i++ }
                out.append('\n')
            } else i++
        }
        return out.toString()
    }

    private fun srtTime(vtt: String): String {
        val m = VTT_TIME.find(vtt) ?: return "00:00:00,000"
        val hours = m.groupValues[1].removeSuffix(":").ifEmpty { "00" }.padStart(2, '0')
        return "$hours:${m.groupValues[2]},${m.groupValues[3]}"
    }
}
