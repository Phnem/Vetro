package com.example.myapplication.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspacePageTest {

    @Test
    fun `order is Home Settings Books`() {
        // Порядок слева направо — решение пользователя, а не деталь реализации: он задаёт и
        // позицию в доке, и позицию страницы в пейджере.
        assertEquals(
            listOf(
                WorkspacePage.HOME,
                WorkspacePage.SETTINGS,
                WorkspacePage.BOOKS,
            ),
            WorkspacePage.Ordered,
        )
        assertEquals(3, WorkspacePage.PageCount)
    }

    @Test
    fun `only swipeable sections are pages`() {
        // Всё, что открывается поверх текущей страницы, страницей быть не должно: попав в
        // пейджер, оно стало бы доступно свайпом, а меню и разовые действия так не работают.
        val notPages = listOf("STATS", "SYNC", "ADD", "FRAME", "MENU", "TTM", "DONATE")
        notPages.forEach { name ->
            assert(WorkspacePage.Ordered.none { it.name == name }) {
                "$name вернулся в страницы, хотя живёт в меню дока"
            }
        }
    }

    @Test
    fun `home is the leftmost page and the starting one`() {
        assertEquals(WorkspacePage.HOME, WorkspacePage.Start)
        assertEquals(0, WorkspacePage.HOME.index)
    }

    @Test
    fun `index round trip`() {
        WorkspacePage.Ordered.forEach { page ->
            assertEquals(page, WorkspacePage.ofIndex(page.index))
        }
    }

    @Test
    fun `out of range index falls back to the start page`() {
        assertEquals(WorkspacePage.Start, WorkspacePage.ofIndex(-1))
        assertEquals(WorkspacePage.Start, WorkspacePage.ofIndex(WorkspacePage.PageCount))
        assertEquals(WorkspacePage.Start, WorkspacePage.ofIndex(Int.MAX_VALUE))
    }

    @Test
    fun `back from any section returns to home`() {
        listOf(
            WorkspacePage.BOOKS,
            WorkspacePage.SETTINGS,
        ).forEach { page ->
            assertEquals("back from $page", WorkspacePage.HOME, WorkspacePage.backTargetFrom(page))
        }
    }

    @Test
    fun `back from home is not intercepted so the system closes the app`() {
        assertNull(WorkspacePage.backTargetFrom(WorkspacePage.HOME))
    }
}
