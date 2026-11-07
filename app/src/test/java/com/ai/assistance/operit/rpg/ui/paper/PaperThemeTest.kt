package com.ai.assistance.operit.rpg.ui.paper

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperThemeTest {
    @Test
    fun sixTonesMeetContrastAgainstWarmOnAccent() {
        val onAccent = Color(0xFFF3EEE4)
        assertEquals(6, PaperTones.size)
        PaperTones.forEach { tone ->
            val ratio = paperContrast(tone.color, onAccent)
            assertTrue("${tone.id} contrast on accent is $ratio, expected at least 4.5", ratio >= 4.5)
        }
    }
}
