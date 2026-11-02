package com.ai.assistance.operit.rpg.backup

import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class LegacyBackupCompatibilityTest {
    @Test fun importsFrozenPublishedFormatWithoutLosingChecksOrClues() = runBlocking {
        val store = InMemoryGameStore()
        val service = RpgBackupService(store)
        val original = javaClass.getResourceAsStream("/compat/v0.1.0.rpgsave.zip")!!.use { service.preview(it) }
        assertEquals("0.1.0 兼容战役", original.title)
        assertEquals(4, original.state.tick)
        assertEquals("archive", original.state.locationId)
        assertEquals(2, original.state.discoveredClues.size)
        assertEquals(30, original.turns.last().result!!.dice!!.roll)
        val id = service.importAsNew(original)
        val imported = store.list().single()
        assertNotEquals(original.id, id)
        assertEquals(original.state.hp, imported.state.hp)
        assertEquals(original.state.inventory, imported.state.inventory)
        assertEquals(original.state.knownFacts, imported.state.knownFacts)
        assertEquals(original.events.map { it.actionId to it.roll }, imported.events.map { it.actionId to it.roll })
        assertEquals(original.state.discoveredClues.map { it.title to it.content }, imported.state.discoveredClues.map { it.title to it.content })
        assertEquals(RpgBackupService.UNBOUND, imported.model)
        val output = ByteArrayOutputStream()
        service.export(id, output)
        val restored = service.preview(output.toByteArray().inputStream())
        assertEquals(imported, restored)
    }
}
