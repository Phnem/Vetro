package com.example.myapplication.audiobooks.domain.source

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalAudioOrderTest {
    @Test fun ordersNumberedPartsByValue() {
        assertEquals(
            listOf("Disc 1/2.mp3", "Disc 1/10.mp3", "Disc 2/1.mp3"),
            listOf("Disc 2/1.mp3", "Disc 1/10.mp3", "Disc 1/2.mp3").sortedWith(NaturalAudioOrder),
        )
    }
}
