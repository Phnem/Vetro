package com.example.myapplication.data.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JsonMapFileStoreTest {

    @Serializable
    data class Item(val name: String, val count: Int = 0)

    private val dir: File = Files.createTempDirectory("jsonmapstore").toFile()
    private val file = File(dir, "items_v1.json")
    private val errors = mutableListOf<String>()

    private fun store() = JsonMapFileStore(file, Item.serializer(), "test", logError = { m, _ -> errors += m })

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `update persists and a new instance reads it back`() = runBlocking {
        store().update { it + ("a" to Item("A", 1)) + ("b" to Item("B")) }

        val reopened = store()
        reopened.ensureLoaded()
        assertEquals(Item("A", 1), reopened["a"])
        assertEquals(2, reopened.value.size)
        assertFalse(File(dir, "items_v1.json.tmp").exists())
    }

    @Test
    fun `unchanged map does not touch the file`() = runBlocking {
        val s = store()
        s.update { it + ("a" to Item("A")) }
        val modified = file.lastModified()
        file.setLastModified(modified - 10_000)
        s.update { it + ("a" to Item("A")) }
        assertEquals(modified - 10_000, file.lastModified())
    }

    @Test
    fun `in-memory updates reach disk only on flush`() = runBlocking {
        val s = store()
        s.updateInMemory { it + ("a" to Item("A")) }
        assertEquals(Item("A"), s["a"])
        assertFalse(file.exists())

        s.flush()
        val reopened = store()
        reopened.ensureLoaded()
        assertEquals(Item("A"), reopened["a"])
    }

    @Test
    fun `corrupt file reads as empty and is replaced on next write`() = runBlocking {
        file.writeText("{not json")
        val s = store()
        s.ensureLoaded()
        assertTrue(s.value.isEmpty())
        assertEquals(1, errors.size)

        s.update { it + ("a" to Item("A")) }
        val reopened = store()
        reopened.ensureLoaded()
        assertEquals(Item("A"), reopened["a"])
    }

    @Test
    fun `disk format is a plain json object keyed by id`() = runBlocking {
        store().update { it + ("a" to Item("A", 2)) }
        val text = file.readText()
        assertTrue(text, text.startsWith("{\"a\":{"))
        assertNull(store()["a"]) // до ensureLoaded карта пустая
    }
}
