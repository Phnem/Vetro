package com.example.myapplication.audiobooks.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSourcesTest {
    @Test
    fun `links of private servers are recognised`() {
        assertTrue(PrivateSources.carriesCredentials("https://home.example/api/items/li_1/cover?token=abc"))
        assertTrue(PrivateSources.carriesCredentials("https://home.example/rest/getCoverArt.view?u=me&t=abc&s=xyz&id=1"))
    }

    @Test
    fun `public covers pass`() {
        assertFalse(PrivateSources.carriesCredentials("https://archive.org/services/img/book"))
        assertFalse(PrivateSources.carriesCredentials("https://cdn.example/covers/1.jpg?token=signed"))
    }
}
