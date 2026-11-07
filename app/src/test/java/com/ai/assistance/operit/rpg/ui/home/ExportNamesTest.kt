package com.ai.assistance.operit.rpg.ui.home

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamesTest {
    private val date = LocalDate.of(2026, 11, 8)

    @Test
    fun backupNameUsesCampaignTitleDateAndD20SaveExtension() {
        assertEquals("笑傲江湖-20261108.d20save", ExportNames.fileName("笑傲江湖", date, "d20save"))
    }

    @Test
    fun markdownNameUsesTheSameDatePattern() {
        assertEquals("笑傲江湖-20261108.md", ExportNames.fileName("笑傲江湖", date, "md"))
    }

    @Test
    fun charactersThatCannotAppearInFileNamesAreReplaced() {
        assertEquals("上_下-20261108.md", ExportNames.fileName("上/下", date, "md"))
    }
}
