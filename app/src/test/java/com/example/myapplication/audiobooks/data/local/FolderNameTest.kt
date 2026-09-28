package com.example.myapplication.audiobooks.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNameTest {

    @Test
    fun `author dash title with narrator in brackets`() {
        val f = FolderName.parse("Лю Цысинь — Задача трёх тел (читает Князев Игорь)")
        assertEquals("Задача трёх тел", f.title)
        assertEquals("Лю Цысинь", f.author)
        assertEquals("Князев Игорь", f.narrator)
    }

    @Test
    fun `plain hyphen and square brackets`() {
        val f = FolderName.parse("Stephen King - The Shining [Campbell Scott]")
        assertEquals("The Shining", f.title)
        assertEquals("Stephen King", f.author)
        assertEquals("Campbell Scott", f.narrator)
    }

    @Test
    fun `title only stays whole, a year in brackets is not a narrator`() {
        val f = FolderName.parse("Мастер и Маргарита (1967)")
        assertEquals("Мастер и Маргарита", f.title)
        assertNull(f.author)
        assertNull(f.narrator)
        assertEquals("Spider-Man", FolderName.parse("Spider-Man").title)
    }
}
