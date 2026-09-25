package com.example.myapplication.media.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizeHeadersTest {
    @Test
    fun dropsCrLfInValues() {
        val out = SanitizeHeaders.sanitize(
            mapOf(
                "Referer" to "https://ok.example/\r\nX-Injected: 1",
                "User-Agent" to "Vetro",
                "Bad\nKey" to "x",
            )
        )
        assertEquals("https://ok.example/ X-Injected: 1", out["Referer"])
        assertEquals("Vetro", out["User-Agent"])
        assertTrue(out.keys.none { it.contains('\n') })
    }

}
