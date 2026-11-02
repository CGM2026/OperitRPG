package com.ai.assistance.operit.rpg.backup

import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RpgBackupServiceTest {
    private val json = RpgJson.instance
    private suspend fun fixture(): Pair<InMemoryGameStore, String> {
        val store = InMemoryGameStore()
        val c = TurnCoordinator(store, FakeModelGateway())
        val id = c.createCampaign("测试战役", "林舟", ModelConfiguration("private-config", "provider", "model", "fingerprint"))
        c.submit(id, "one", 0, "move.yard_office")
        c.submit(id, "two", 1, "inspect.office_note")
        return store to id
    }
    private suspend fun archive(store: GameStore, id: String): ByteArray = ByteArrayOutputStream().also { RpgBackupService(store).export(id, it) }.toByteArray()
    private fun unpack(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; result[e.name] = z.readBytes() } }
        return result
    }
    private fun pack(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
    }.toByteArray()
    private fun rehash(entries: LinkedHashMap<String, ByteArray>): ByteArray {
        val manifest = json.parseToJsonElement(entries.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        val files = manifest.getValue("files").jsonObject.keys.associateWith { name ->
            val b = entries.getValue(name)
            buildJsonObject { put("bytes", b.size); put("sha256", MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }) }
        }
        entries["manifest.json"] = JsonObject(manifest + ("files" to JsonObject(files))).toString().toByteArray()
        return pack(entries)
    }
    private suspend fun rejected(bytes: ByteArray) {
        var failed = false
        try { RpgBackupService(InMemoryGameStore()).preview(ByteArrayInputStream(bytes)) } catch (_: Exception) { failed = true }
        assertTrue("Invalid archive was accepted", failed)
    }
    @Test fun roundTripRemapsAllReferencesAndNeverCopiesConfigurationOrAttempts() = runBlocking {
        val (store, id) = fixture(); val bytes = archive(store, id)
        val entries = unpack(bytes)
        assertEquals(setOf("manifest.json", "campaign.json", "turns.jsonl", "events.jsonl", "narrations.jsonl"), entries.keys)
        assertFalse(entries.values.any { it.toString(Charsets.UTF_8).contains("private-config") })
        val destination = InMemoryGameStore(); val service = RpgBackupService(destination)
        val preview = service.preview(ByteArrayInputStream(bytes)); assertTrue(destination.list().isEmpty())
        val newId = service.importAsNew(preview); val imported = destination.list().single()
        assertNotEquals(id, newId); assertEquals(RpgBackupService.UNBOUND, imported.model)
        assertTrue(imported.turns.all { it.campaignId == newId && it.model == RpgBackupService.UNBOUND && it.attempts.isEmpty() && it.attemptId == null })
        assertTrue(imported.events.all { e -> e.campaignId == newId && imported.turns.any { it.id == e.turnId } })
        assertTrue(imported.turns.map { it.id }.intersect(preview.turns.map { it.id }.toSet()).isEmpty())
        assertTrue(imported.events.map { it.id }.intersect(preview.events.map { it.id }.toSet()).isEmpty())
        val clue = imported.state.discoveredClues.single()
        assertEquals(imported.turns.last().id, clue.sourceTurnId)
        assertEquals(clue, imported.turns.last().result!!.addedClues.single())
        assertEquals(imported.state, RainyDock.replay(RainyDock.initialState(), imported.events))
        service.export(newId, ByteArrayOutputStream())
    }
    @Test fun rejectsHashMismatchAndMissingFile() = runBlocking {
        val (store, id) = fixture(); val entries = unpack(archive(store,id))
        entries["campaign.json"] = entries.getValue("campaign.json") + ' '.code.toByte()
        rejected(pack(entries)); entries.remove("events.jsonl"); rejected(pack(entries))
    }
    @Test fun rejectsUnknownAndTraversalPaths() = runBlocking {
        val (store,id) = fixture(); val original = unpack(archive(store,id))
        for (name in listOf("../campaign.json", "/campaign.json", "C:/campaign.json", "secret.txt")) {
            val entries = LinkedHashMap(original); entries[name] = byteArrayOf(1); rejected(pack(entries))
        }
    }
    @Test fun rejectsDuplicateZipNamesAndTruncatedCentralDirectory() = runBlocking {
        val (store,id) = fixture(); val entries = unpack(archive(store,id))
        entries["abcdef.json"] = byteArrayOf(1)
        // Equal length replacements change both local and central names, creating a duplicate entry.
        val duplicate = pack(entries).toString(Charsets.ISO_8859_1).replace("abcdef.json", "turns.jsonl").toByteArray(Charsets.ISO_8859_1)
        rejected(duplicate)
        val good = archive(store,id); rejected(good.copyOf(good.size - 22))
    }
    @Test fun rejectsCompressedAndExpandedSizeLimits() = runBlocking {
        rejected(ByteArray(10 * 1024 * 1024 + 1))
        val bomb = pack(mapOf("campaign.json" to ByteArray(20 * 1024 * 1024 + 1)))
        rejected(bomb)
    }
    @Test fun rejectsInvalidUtf8AndHigherFormatEvenWithFreshHashes() = runBlocking {
        val (store,id) = fixture(); val original = unpack(archive(store,id))
        val invalid = LinkedHashMap(original); invalid["turns.jsonl"] = byteArrayOf(0xC3.toByte(), 0x28); rejected(rehash(invalid))
        val higher = LinkedHashMap(original)
        higher["manifest.json"] = higher.getValue("manifest.json").toString(Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":2").toByteArray()
        rejected(pack(higher))
    }
    @Test fun rejectsForgedProjectionResultSequenceAndForeignKeysAfterRehash() = runBlocking {
        val (store,id) = fixture(); val original = unpack(archive(store,id))
        val mutations = listOf<Pair<String, (String) -> String>>(
            "campaign.json" to { s -> s.replace("\"hp\":6", "\"hp\":5") },
            "turns.jsonl" to { s -> s.replace("\"timeCost\":1", "\"timeCost\":0") },
            "events.jsonl" to { s -> s.replace("\"sequence\":1", "\"sequence\":2") },
            "events.jsonl" to { s -> s.replace("\"campaignId\":\"$id\"", "\"campaignId\":\"foreign\"") }
        )
        mutations.forEach { (file, mutate) -> val entries = LinkedHashMap(original); entries[file] = mutate(entries.getValue(file).toString(Charsets.UTF_8)).toByteArray(); rejected(rehash(entries)) }
    }
    @Test fun rejectsPendingExportAndImportWithNoPartialWrite() = runBlocking {
        val (store,id) = fixture(); val service = RpgBackupService(store)
        val preview = service.preview(ByteArrayInputStream(archive(store,id)))
        preview.turns.last().stage = TurnStage.RESOLVED
        var failed = false
        try { service.importAsNew(preview) } catch (_: Exception) { failed = true }
        assertTrue(failed); assertEquals(1, store.list().size)
        store.transaction(id) { it.turns.last().stage = TurnStage.NARRATING }
        val output = ByteArrayOutputStream(); failed = false
        try { service.export(id,output) } catch (_: Exception) { failed = true }
        assertTrue(failed); assertEquals(0,output.size())
    }
    @Test fun revalidatesPreviewBeforeCreateAndPropagatesAtomicStoreFailure() = runBlocking {
        val (store,id) = fixture(); val service = RpgBackupService(store)
        val preview = service.preview(ByteArrayInputStream(archive(store,id)))
        preview.model = ModelConfiguration("bad", "bad", "bad", "bad")
        var failed = false; try { service.importAsNew(preview) } catch (_: Exception) { failed = true }
        assertTrue(failed); assertEquals(1,store.list().size)
        val failing = object : GameStore {
            var creates = 0
            override suspend fun create(record: CampaignRecord) { creates++; throw IOExceptionForTest() }
            override suspend fun list() = emptyList<CampaignRecord>()
            override suspend fun <T> transaction(campaignId: String, block: (CampaignRecord) -> T): T = error("unused")
        }
        val clean = service.preview(ByteArrayInputStream(archive(store,id)))
        failed = false; try { RpgBackupService(failing).importAsNew(clean) } catch (_: IOExceptionForTest) { failed = true }
        assertTrue(failed); assertEquals(1,failing.creates); assertTrue(failing.list().isEmpty())
    }
    private class IOExceptionForTest : RuntimeException()
}
