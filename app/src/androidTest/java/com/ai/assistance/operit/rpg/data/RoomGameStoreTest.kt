package com.ai.assistance.operit.rpg.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real on-disk Room tests. Close/reopen proves disk persistence, not force-stop equivalence. */
@RunWith(AndroidJUnit4::class)
class RoomGameStoreTest {
    private lateinit var context: Context
    private lateinit var database: RpgDatabase
    private lateinit var databaseName: String
    private lateinit var store: RoomGameStore
    private val model = ModelConfiguration("test", "DEMO", "script-v1", "test-v1")

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "rpg-instrumentation-${UUID.randomUUID()}.db"
        open()
    }
    private fun open() {
        database = Room.databaseBuilder(context, RpgDatabase::class.java, databaseName).build()
        store = RoomGameStore(database)
    }
    private fun reopen() { database.close(); open() }
    @After fun tearDown() { database.close(); context.deleteDatabase(databaseName) }

    @Test fun committedResultSurvivesDiskReopenAndCannotReroll() = runBlocking {
        val dice = SequenceDice(90)
        var engine = TurnCoordinator(store, FakeModelGateway(), dice)
        val id = engine.createCampaign("disk", "林舟", model)
        val result = engine.submitAction(id, "first", 0, "climb.archive_window")
        assertEquals(OutcomeCode.APPLIED, result.code)
        val before = store.list().single()
        assertEquals(1, dice.consumed)
        reopen()
        // An empty dice source would throw if persisted resolution were executed again.
        engine = TurnCoordinator(store, FakeModelGateway(), SequenceDice())
        assertEquals(OutcomeCode.ALREADY_APPLIED, engine.resolvePrepared(id, result.turnId!!, 0).code)
        val after = store.list().single()
        assertEquals(before, after)
        assertEquals(90, after.turns.single().result!!.dice!!.roll)
        assertEquals(5, after.state.hp)
        assertEquals(1, after.state.tick)
        assertEquals(after.state, RainyDock.replay(RainyDock.initialState(), after.events))
    }

    @Test fun twoCoordinatorsResolveOnePreparedTurnExactlyOnce() = runBlocking {
        val record = CampaignRecord("race", "race", RainyDock.initialState(), model, 0)
        val plan = Plan(PlanKind.ACTION, actionId = "climb.archive_window", publicIntent = "攀爬档案室窗户")
        record.turns += Turn("same-turn", record.id, 1, "same-message", "攀爬", TurnStage.PREPARED, 0, model, 0,
            plan = plan, planHash = sha256(RpgJson.instance.encodeToString(plan)), actionHash = RainyDock.actionHash(plan.actionId!!))
        store.create(record)
        val dice = SequenceDice(90)
        val a = TurnCoordinator(store, FakeModelGateway(), dice)
        val b = TurnCoordinator(store, FakeModelGateway(), dice)
        val outcomes = coroutineScope {
            listOf(async(Dispatchers.Default) { a.resolvePrepared(record.id, "same-turn", 0) },
                async(Dispatchers.Default) { b.resolvePrepared(record.id, "same-turn", 0) }).awaitAll()
        }
        assertEquals(1, outcomes.count { it.code == OutcomeCode.APPLIED })
        assertEquals(1, outcomes.count { it.code == OutcomeCode.ALREADY_APPLIED })
        assertEquals(1, dice.consumed)
        reopen()
        val saved = store.list().single()
        assertEquals(1, saved.events.size)
        assertEquals(1L, saved.state.revision)
        assertEquals(5, saved.state.hp)
    }

    @Test fun transactionFailureAndDuplicateIdsLeaveNoPartialDiskWrites() = runBlocking {
        val engine = TurnCoordinator(store, FakeModelGateway())
        val id = engine.createCampaign("rollback", "林舟", model)
        expectFailure {
            store.transaction(id) { r -> r.state = r.state.copy(hp = 1); error("injected before commit") }
        }
        val initial = store.list().single()
        expectFailure { store.create(initial) }
        val success = engine.submitAction(id, "unique-message", 0, "wait")
        assertEquals(OutcomeCode.APPLIED, success.code)
        assertEquals(OutcomeCode.ALREADY_APPLIED, engine.submitAction(id, "unique-message", 0, "wait").code)
        expectFailure { store.transaction(id) { r ->
            val previous = r.turns.single()
            r.turns += previous.copy(id = "different-turn", ordinal = 2)
        } }
        reopen()
        val saved = store.list().single()
        assertEquals(6, saved.state.hp)
        assertEquals(1, saved.state.tick)
        assertEquals(1, saved.turns.size)
        assertEquals(1, saved.events.size)
    }

    @Test fun campaignIsolationSurvivesDiskReopen() = runBlocking {
        val engine = TurnCoordinator(store, FakeModelGateway())
        val a = engine.createCampaign("A", "同名", model)
        val b = engine.createCampaign("B", "同名", model)
        engine.submitAction(a, "same-client-id", 0, "move.yard_office")
        engine.submitAction(a, "note", 1, "inspect.office_note")
        engine.submitAction(b, "same-client-id", 0, "move.yard_dock")
        reopen()
        val restored = TurnCoordinator(store, FakeModelGateway())
        val viewA = restored.publicView(a)
        val viewB = restored.publicView(b)
        assertEquals("c1", viewA.clues.single().id)
        assertTrue(viewB.clues.isEmpty())
        assertEquals("dock", viewB.location.id)
        assertEquals(OutcomeCode.INVALID_PLAN, restored.submitAction(b, "rescue", 1, "rescue.boatman_help").code)
        assertFalse(RpgJson.instance.encodeToString(viewB).contains("K17"))
        assertEquals(2, store.list().size)
    }

    @Test fun receivedAndInterruptedRequestsPersistWithoutAutomaticNetwork() = runBlocking {
        var calls = 0
        val gateway = object : ModelGateway {
            override suspend fun plan(request: ModelRequest): ModelReply { calls++; return FakeModelGateway().plan(request) }
            override suspend fun narrate(request: ModelRequest): ModelReply { calls++; return FakeModelGateway().narrate(request) }
            override suspend fun cancel(attemptId: String) = Unit
        }
        val record = CampaignRecord("interrupted", "recover", RainyDock.initialState(), model, 0)
        record.turns += Turn("t", record.id, 1, "m", "wait", TurnStage.PLANNING, 0, model, 0,
            attemptId = "lost", attempts = mutableListOf(RequestAttempt("lost", TurnStage.PLANNING, 1, model.fingerprint)))
        store.create(record)
        reopen()
        val engine = TurnCoordinator(store, gateway)
        assertEquals("INTERRUPTED", engine.recover(record.id).turns.single().errorCode)
        assertEquals(0, calls)
        assertEquals(OutcomeCode.APPLIED, engine.retryStage(record.id, "t", TurnStage.PLANNING).code)
        assertEquals(2, calls)
        reopen()
        assertEquals(TurnStage.COMPLETE, store.list().single().turns.single().stage)
        assertEquals(1, store.list().single().state.tick)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failure: Exception? = null
        try { block() } catch (e: Exception) { failure = e }
        assertNotNull("Expected transaction/constraint failure", failure)
    }
}
