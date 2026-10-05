package com.example.myapplication.manga.translate.pipeline

import com.example.myapplication.manga.translate.TranslationPrompt
import com.example.myapplication.manga.translate.TranslationResponse
import com.example.myapplication.network.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineUnitTest {

    private fun box(l: Number, t: Number, r: Number, b: Number) = Box(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    private fun det(kind: DetectionKind, b: Box, s: Float = 0.9f) = Detection(kind, b, s)

    // ---- RgbImage ----

    @Test
    fun resizing_a_flat_image_keeps_its_color_and_averages_a_checkerboard() {
        val flat = RgbImage(8, 8, IntArray(64) { 0x336699 })
        assertTrue(flat.resized(2, 2).pixels.all { it == 0x336699 })

        val checker = RgbImage(8, 8, IntArray(64) { if ((it % 8 + it / 8) % 2 == 0) 0xFFFFFF else 0x000000 })
        val small = checker.resized(2, 2)
        // Честное усреднение (а не выборка одного пикселя): серый, а не чисто чёрный или белый.
        small.pixels.forEach { p -> assertTrue("pixel=${p.toString(16)}", (p and 0xFF) in 100..155) }
    }

    @Test
    fun crop_is_clamped_to_the_image() {
        val img = RgbImage(10, 10, IntArray(100) { it })
        val c = img.crop(Box(-5f, -5f, 4f, 3f))
        assertEquals(4, c.width)
        assertEquals(3, c.height)
        assertEquals(0, c.pixels[0])
    }

    // ---- детектор: нарезка длинных полос ----

    @Test
    fun a_normal_page_is_one_tile_and_a_long_strip_is_overlapping_tiles() {
        assertEquals(1, TextRegionDetector.tilesFor(1115, 1600).size)
        val tiles = TextRegionDetector.tilesFor(800, 12_000)
        assertTrue(tiles.size > 6)
        assertEquals(0, tiles.first().top)
        assertEquals(12_000, tiles.last().bottom)
        // Соседние куски перекрываются больше, чем высота типичного блока текста (пол-ширины страницы).
        tiles.zipWithNext().forEach { (a, b) -> assertTrue("a=$a b=$b", a.bottom - b.top >= 400) }
    }

    @Test
    fun nms_drops_duplicates_of_the_same_kind_only() {
        val kept = nonMaxSuppression(
            listOf(
                det(DetectionKind.FREE_TEXT, box(0, 0, 100, 100), 0.9f),
                det(DetectionKind.FREE_TEXT, box(5, 5, 105, 105), 0.8f),
                det(DetectionKind.BUBBLE, box(0, 0, 100, 100), 0.7f),
            ),
            0.6f,
        )
        assertEquals(2, kept.size)
    }

    // ---- склейка в реплики ----

    @Test
    fun columns_in_one_bubble_become_one_region_read_right_to_left() {
        val bubble = box(0, 0, 300, 300)
        val right = box(180, 60, 240, 240)
        val left = box(60, 70, 120, 230)
        val regions = RegionGrouping.group(
            listOf(
                det(DetectionKind.BUBBLE, bubble),
                det(DetectionKind.BUBBLE_TEXT, left),
                det(DetectionKind.BUBBLE_TEXT, right),
            ),
            rightToLeft = true,
        )
        assertEquals(1, regions.size)
        assertEquals(listOf(right, left), regions.single().textBoxes)
        assertEquals(bubble, regions.single().bubble)
        // Для вебтуна (слева направо) порядок колонок обратный.
        val ltr = RegionGrouping.group(
            listOf(det(DetectionKind.BUBBLE, bubble), det(DetectionKind.BUBBLE_TEXT, left), det(DetectionKind.BUBBLE_TEXT, right)),
            rightToLeft = false,
        )
        assertEquals(listOf(left, right), ltr.single().textBoxes)
    }

    @Test
    fun a_text_goes_to_the_smallest_bubble_that_holds_it() {
        val outer = box(0, 0, 500, 500)
        val inner = box(100, 100, 300, 300)
        val text = box(130, 130, 270, 270)
        val regions = RegionGrouping.group(
            listOf(det(DetectionKind.BUBBLE, outer), det(DetectionKind.BUBBLE, inner), det(DetectionKind.BUBBLE_TEXT, text)),
            rightToLeft = true,
        )
        assertEquals(1, regions.size)
        assertEquals(inner, regions.single().bubble)
    }

    @Test
    fun a_bubble_text_without_a_bubble_stays_a_bubble_region_and_free_text_stays_free() {
        val regions = RegionGrouping.group(
            listOf(
                det(DetectionKind.BUBBLE_TEXT, box(0, 0, 50, 100)),
                det(DetectionKind.FREE_TEXT, box(400, 0, 450, 100)),
            ),
            rightToLeft = true,
        )
        assertEquals(setOf(RegionKind.BUBBLE, RegionKind.FREE), regions.map { it.kind }.toSet())
    }

    @Test
    fun adjacent_free_columns_merge_but_distant_ones_do_not() {
        val near = RegionGrouping.group(
            listOf(det(DetectionKind.FREE_TEXT, box(100, 0, 160, 200)), det(DetectionKind.FREE_TEXT, box(170, 5, 230, 205))),
            rightToLeft = true,
        )
        assertEquals(1, near.size)
        assertEquals(2, near.single().textBoxes.size)

        val far = RegionGrouping.group(
            listOf(det(DetectionKind.FREE_TEXT, box(100, 0, 160, 200)), det(DetectionKind.FREE_TEXT, box(600, 5, 660, 205))),
            rightToLeft = true,
        )
        assertEquals(2, far.size)
    }

    @Test
    fun reading_order_is_rows_top_to_bottom_and_right_to_left_inside_a_row() {
        val topRight = det(DetectionKind.FREE_TEXT, box(600, 10, 700, 200))
        val topLeft = det(DetectionKind.FREE_TEXT, box(100, 20, 200, 210))
        val bottom = det(DetectionKind.FREE_TEXT, box(350, 600, 450, 800))
        val order = RegionGrouping.group(listOf(bottom, topLeft, topRight), rightToLeft = true).map { it.textBounds }
        assertEquals(listOf(topRight.box, topLeft.box, bottom.box), order)
        val ltr = RegionGrouping.group(listOf(bottom, topLeft, topRight), rightToLeft = false).map { it.textBounds }
        assertEquals(listOf(topLeft.box, topRight.box, bottom.box), ltr)
    }

    // ---- фильтр реплик ----

    @Test
    fun page_numbers_and_stray_marks_are_not_translated_but_reactions_are() {
        assertFalse(PageAnalyzer.isTranslatable("145"))
        assertFalse(PageAnalyzer.isTranslatable(""))
        assertFalse(PageAnalyzer.isTranslatable("、"))
        assertTrue(PageAnalyzer.isTranslatable("..."))
        assertTrue(PageAnalyzer.isTranslatable("?!"))
        assertTrue(PageAnalyzer.isTranslatable("姉さん..."))
    }

    // ---- запрос и ответ модели ----

    @Test
    fun user_message_follows_the_agreed_format() {
        val message = TranslationPrompt.userMessage(
            TranslationPrompt.WorkContext("Chainsaw Man", "Denji is a young devil hunter."),
            AppLanguage.RU,
            listOf(1 to "Huh?!", 2 to "What the hell are you doing?!"),
        )
        assertEquals(
            "{Chainsaw Man}{Denji is a young devil hunter.}{TARGET_LANGUAGE: RU}\n\n[1] Huh?!\n[2] What the hell are you doing?!",
            message,
        )
    }

    @Test
    fun braces_and_newlines_in_the_context_cannot_break_the_format() {
        val message = TranslationPrompt.userMessage(
            TranslationPrompt.WorkContext("A {weird} title", "line one\nline {two}"),
            AppLanguage.EN,
            listOf(7 to "x"),
        )
        assertEquals("{A (weird) title}{line one line (two)}{TARGET_LANGUAGE: EN}\n\n[7] x", message)
    }

    @Test
    fun a_long_description_is_cut() {
        val message = TranslationPrompt.userMessage(
            TranslationPrompt.WorkContext("T", "x".repeat(5_000)),
            AppLanguage.RU,
            listOf(1 to "a"),
        )
        assertTrue(message.length < 900)
    }

    @Test
    fun the_agreed_example_response_is_parsed_by_ids() {
        val parsed = TranslationResponse.parse(
            "[1] А?!\n[2] Ты какого хрена творишь?!\n[3] Хех...\n[4] Тс-с-с!\n[5] БАХ",
            listOf(1, 2, 3, 4, 5),
        )
        assertEquals("Тс-с-с!", parsed.translations[4])
        assertEquals(5, parsed.translations.size)
        assertTrue(parsed.missing.isEmpty())
    }

    @Test
    fun a_missing_id_is_reported_and_unknown_or_repeated_ids_are_ignored() {
        val parsed = TranslationResponse.parse(
            "Sure! Here you go:\n[1] один\n[3] три\n[9] лишний\n[1] повтор",
            listOf(1, 2, 3),
        )
        assertEquals(mapOf(1 to "один", 3 to "три"), parsed.translations)
        assertEquals(listOf(2), parsed.missing)
    }

    @Test
    fun multi_line_fragments_and_code_fences_are_handled() {
        val parsed = TranslationResponse.parse(
            "```\n[1] первая строка\nвторая строка\n[2]: ответ\n```",
            listOf(1, 2),
        )
        assertEquals("первая строка\nвторая строка", parsed.translations[1])
        assertEquals("ответ", parsed.translations[2])
    }

    @Test
    fun numbered_list_is_accepted_when_the_model_drops_the_brackets() {
        val parsed = TranslationResponse.parse("1. привет\n2. пока", listOf(1, 2))
        assertEquals(mapOf(1 to "привет", 2 to "пока"), parsed.translations)
    }

    @Test
    fun an_empty_translation_counts_as_missing() {
        val parsed = TranslationResponse.parse("[1] \n[2] ok", listOf(1, 2))
        assertEquals(listOf(1), parsed.missing)
    }
}
