package com.ai.assistance.operit.rpg.agent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.RuleAdapters
import com.ai.assistance.operit.rpg.agent.rules.initialWorld
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AgentDatabase
    private lateinit var name: String
    private lateinit var store: RoomAgentStore

    private val module: ValidatedModule get() {
        val ref = SourceRef("module/sources/rules.md", sha256("检定使用百分骰"), "check", "module_rule")
        val base = ValidatedModule(
            ModuleManifest(1, "test", "1.0.0", "测试", "test", "公开简介", 1, "generic-d100-v1", "1", emptyList()),
            "test-hash",
            ModuleWorld(
                "test:room",
                listOf(CharacterTemplate("test:hero", "旅人", mapOf("调查" to 60), mapOf("hp" to 6))),
                listOf(Location("test:room", "房间", "", emptyList())),
                listOf(NpcState("test:npc", "船工", "test:room", "idle", "谨慎", "守约")),
                emptyList(),
                listOf(Fact("test:secret", "暗门位置", Visibility(Audience.GM), true, ref)),
                emptyList(),
                emptyList(),
                GenerationPolicy(emptySet(), 0, setOf("test:secret")),
            ),
            ModuleRules(
                "generic-d100-v1", "1", setOf("调查"), mapOf("hp" to ResourceBounds(0, 6)), mapOf("普通" to 0),
                setOf("time", "npc_status"), listOf(RuleEvidence("test:check", ref, "检定使用百分骰", Visibility(Audience.PUBLIC))), "分钟",
            ),
            mapOf("module/sources/rules.md" to "检定使用百分骰"),
        )
        val files = base.sources + mapOf("module/world.json" to encode(base.world), "module/rules.json" to encode(base.rules))
        val manifest = base.manifest.copy(files = files.toSortedMap().map { FileDigest(it.key, it.value.toByteArray().size.toLong(), sha256(it.value)) })
        return base.copy(manifest = manifest, contentHash = sha256(StrictJson.json.encodeToString(manifest)), originalFiles = files)
    }

    @Before fun setup() {
        name = "fork-test-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(context, AgentDatabase::class.java, name).build()
        store = RoomAgentStore(db)
        RoomModuleCatalog(db).install(module)
        store.createCampaign(
            Campaign(
                "src", "src", VersionRef("test", "1.0.0", module.contentHash),
                VersionRef("generic-d100-v1", "1", module.contentHash), 0, 0,
                initialWorld(module, "test:hero"), false, "local-profile", "local-narrator", 0,
            ),
        )
    }

    @After fun cleanup() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun forkReplaysWorldToTheEventAndCopiesOnlyThePrefix() {
        val ref = SourceRef("module/sources/rules.md", sha256("检定使用百分骰"), "check", "module_rule")
        commit(1, listOf(Effect("time", "world", amount = 10, source = ref)))
        commit(2, listOf(Effect("npc_status", "test:npc", value = "helpful", source = ref)))

        val shelf = RoomCampaignShelf(db)
        val group = shelf.createGroup("第一卷")
        shelf.moveToGroup("src", group)
        shelf.setAvatar("src", "sword", "ink")
        shelf.setFinished("src", true)

        val forked = AgentBackupService(db, context.cacheDir).fork("src", 1)
        val newId = forked.campaignId
        val meta = requireNotNull(db.dao().campaignMeta(newId))
        assertEquals(group, meta.groupId)
        assertEquals("sword", meta.avatarGlyph)
        assertEquals("ink", meta.avatarPair)
        assertFalse(meta.finished)

        val initial = decode<WorldState>(db.dao().initial("src").joinToString("") { it.text })
        val adapter = RuleAdapters.forModule(module)
        val expected = store.events("src", 0, 10).filter { it.seq <= 1 }.fold(initial) { world, event ->
            adapter.applyRecordedEvent(world, event, module)
        }
        val forkedCampaign = requireNotNull(store.loadCampaign(newId))
        assertEquals(canonicalWorld(expected), canonicalWorld(forkedCampaign.world))
        assertEquals(1L, forkedCampaign.lastEventSeq)
        assertEquals(1L, forkedCampaign.revision)
        assertEquals("src（第 1 章起）", forkedCampaign.title)
        assertEquals(1, store.history(newId, null, 20).size)
        assertEquals(1, store.events(newId, 0, 10).size)
        assertEquals(listOf("叙述1"), store.history(newId, null, 20).map { it.narration })
    }

    private fun commit(seq: Long, effects: List<Effect>): Campaign {
        val campaign = requireNotNull(store.loadCampaign("src"))
        val turnId = "src:t$seq"
        val event = WorldEvent(
            RuleAdapters.eventVersion(campaign.rules.id), "src", seq, turnId, effects, emptyList(), null,
            Visibility(Audience.PUBLIC), emptyList(),
        )
        val world = RuleAdapters.forModule(module).applyRecordedEvent(campaign.world, event, module)
        val result = CommittedResult(turnId, seq, true, null, "公开$seq", emptyList())
        store.transaction("src") { tx ->
            tx.appendEvent(event)
            tx.saveCampaign(campaign.copy(world = world, revision = seq, lastEventSeq = seq, nextTurnOrdinal = seq + 1, updatedAt = seq))
            tx.saveTurn(Turn(turnId, "src", seq, "src:m$seq", "行动$seq", campaign.revision, TurnPhase.COMPLETE, result = result, narration = "叙述$seq"))
        }
        return requireNotNull(store.loadCampaign("src"))
    }
}
