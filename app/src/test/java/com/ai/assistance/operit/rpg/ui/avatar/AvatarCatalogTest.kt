package com.ai.assistance.operit.rpg.ui.avatar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarCatalogTest {
    private val source = File("src/main/assets/avatars/avatars.json").readText()

    @Test fun bundledCatalogParsesWithExpectedCounts() {
        val catalog = AvatarCatalog.parse(source)
        assertEquals(1, catalog.version)
        assertEquals(24, catalog.animals.size)
        assertEquals(12, catalog.motifs.size)
        assertEquals(24, catalog.pairs.size)
        assertEquals(24, catalog.animals.map { it.id }.toSet().size)
        assertEquals(12, catalog.motifs.map { it.id }.toSet().size)
        assertEquals(24, catalog.pairs.map { it.id }.toSet().size)
    }

    @Test fun pickIsStableAndCoversEveryPair() {
        val catalog = AvatarCatalog.parse(source)
        val seed = "campaign:the-lost-temple"
        assertEquals(catalog.pick(AvatarKind.CAMPAIGN, seed), catalog.pick(AvatarKind.CAMPAIGN, seed))
        val pairIds = (0 until 1000).map { catalog.pick(AvatarKind.CHARACTER, "character-$it").pairId }.toSet()
        assertEquals(catalog.pairs.map { it.id }.toSet(), pairIds)
    }
}
