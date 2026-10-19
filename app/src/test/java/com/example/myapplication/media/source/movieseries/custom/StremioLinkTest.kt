package com.example.myapplication.media.source.movieseries.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StremioLinkTest {
    @Test
    fun `stremio scheme becomes https`() {
        assertEquals(
            "https://addon.example/cfg/manifest.json",
            StremioLink.normalize("stremio://addon.example/cfg/manifest.json"),
        )
    }

    @Test
    fun `bare server address gets the manifest name`() {
        assertEquals("https://addon.example/manifest.json", StremioLink.normalize("https://addon.example"))
        assertEquals("https://addon.example/a/manifest.json", StremioLink.normalize("https://addon.example/a/"))
    }

    @Test
    fun `address without scheme is accepted`() {
        assertEquals("https://addon.example/manifest.json", StremioLink.normalize("addon.example"))
    }

    @Test
    fun `existing json link and query are kept`() {
        assertEquals("https://a.example/x.json?k=1", StremioLink.normalize("https://a.example/x.json?k=1"))
        assertEquals("https://a.example/manifest.json?k=1", StremioLink.normalize("https://a.example?k=1"))
    }

    @Test
    fun `not a link is rejected`() {
        assertNull(StremioLink.normalize(""))
        assertNull(StremioLink.normalize("two words"))
        assertNull(StremioLink.normalize("ftp://a.example/x"))
        assertNull(StremioLink.normalize("{\"id\":\"x\"}"))
    }
}
