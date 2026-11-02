package com.ai.assistance.operit.rpg.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Frozen schema1 bytes and hashes; intentionally independent of new longform DTOs. */
class LegacyV02FixtureTest {
    companion object {
        private const val SCENARIO_HASH="a3b971e9f26d449e08a99b575dfa660b32f33c12d21b445f5e714045e61facaa"
        private const val PLAN_HASH="49bc1a0ab06387bc3250d5a8a5299f54844231f31a17e103205e55094576b8ab"
        private const val ACTION_HASH="67f5687c0dbf4633f040bd07ea3d83a65e92d69a189d9b614de4530338764c4e"
        private const val PLAN="""{"kind":"ACTION","actionId":"climb.archive_window","requiresConfirmation":true,"publicIntent":"攀爬","npcId":null,"question":null}"""
        private const val MODEL="""{"configurationId":"fixture-v02","providerId":"fake","modelId":"fixture","fingerprint":"fixture-v02"}"""
        private const val BEFORE="""{"playerName":"林舟","hp":6,"maxHp":6,"locationId":"yard","tick":0,"alarm":0,"inventory":{"bandage":1},"discoveredClues":[],"knownFacts":{},"visitedLocations":["yard"],"resolvedActionIds":[],"accessArchive":false,"rescued":false,"status":"ACTIVE","revision":0}"""
        private const val AFTER="""{"playerName":"林舟","hp":5,"maxHp":6,"locationId":"yard","tick":1,"alarm":1,"inventory":{"bandage":1},"discoveredClues":[],"knownFacts":{},"visitedLocations":["yard"],"resolvedActionIds":["climb.archive_window"],"accessArchive":false,"rescued":false,"status":"ACTIVE","revision":1}"""
        private const val RESULT="""{"actionId":"climb.archive_window","dice":{"skill":"攀爬","skillValue":55,"threshold":55,"roll":90,"success":false,"difficulty":"普通"},"publicSummary":"攀爬失败，HP减少1，警戒增加，仍在院门。","timeCost":1,"hpChange":-1,"addedClues":[],"status":"ACTIVE"}"""
        private const val EVENT="""{"id":"event-v02","campaignId":"legacy-v02","turnId":"turn-v02","sequence":1,"type":"ACTION_RESOLVED","actionId":"climb.archive_window","roll":90,"schemaVersion":1,"visibility":"PUBLIC"}"""
    }
    private fun fixture(stage:TurnStage):String {
        val committed=stage in setOf(TurnStage.RESOLVED,TurnStage.NARRATING)
        val planning=stage==TurnStage.PLANNING
        val attempt=if(stage in setOf(TurnStage.PLANNING,TurnStage.NARRATING))"""[{"id":"attempt-v02","stage":"$stage","attemptNo":1,"modelFingerprint":"fixture-v02","status":"ACTIVE","usage":null,"durationMs":0,"errorCode":null}]""" else "[]"
        return """{"id":"legacy-v02","title":"旧版中断夹具","state":${if(committed)AFTER else BEFORE},"model":$MODEL,"createdAt":1000,"updatedAt":1000,"scenarioId":"rainy-dock-v1","scenarioVersion":"1.0.0","scenarioHash":"$SCENARIO_HASH","rulesVersion":"prototype-d100-v1","turns":[{"id":"turn-v02","campaignId":"legacy-v02","ordinal":1,"clientMessageId":"message-v02","inputText":"攀爬","stage":"$stage","baseRevision":0,"model":$MODEL,"createdAt":1000,"attemptId":${if(attempt!="[]")"\"attempt-v02\"" else "null"},"plan":${if(planning)"null" else PLAN},"planHash":${if(planning)"null" else "\"$PLAN_HASH\""},"actionHash":${if(planning)"null" else "\"$ACTION_HASH\""},"result":${if(committed)RESULT else "null"},"narration":null,"errorCode":null,"attempts":$attempt}],"events":[${if(committed)EVENT else ""}]}"""
    }
    private fun verify(stage:TurnStage)=runBlocking {
        assertEquals(SCENARIO_HASH,RainyDock.hash)
        assertEquals(PLAN_HASH,sha256(RpgJson.instance.encodeToString(RpgJson.instance.decodeFromString<Plan>(PLAN))))
        assertEquals(ACTION_HASH,RainyDock.actionHash("climb.archive_window"))
        val loaded=RpgJson.instance.decodeFromString<CampaignRecord>(fixture(stage))
        val canonical=RpgJson.instance.encodeToString(loaded)
        assertEquals(canonical,RpgJson.instance.encodeToString(RpgJson.instance.decodeFromString<CampaignRecord>(canonical)))
        val store=InMemoryGameStore(); store.create(loaded); val dice=SequenceDice(90)
        var plans=0; var narratives=0
        val gateway=object:ModelGateway {
            override suspend fun cancel(attemptId:String)=Unit
            override suspend fun plan(request:ModelRequest):ModelReply { plans++; return ModelReply("""{"schema_version":1,"kind":"action","action_id":"climb.archive_window","requires_confirmation":true,"public_intent":"攀爬"}""") }
            override suspend fun narrate(request:ModelRequest):ModelReply { narratives++; return ModelReply("攀爬失败，仍在院门。") }
        }
        val coordinator=TurnCoordinator(store,gateway,dice)
        val recovered=coordinator.recover(loaded.id)
        assertEquals(0,plans); assertEquals(0,narratives); assertEquals(0,dice.consumed)
        if(stage!=TurnStage.PLANNING) { assertEquals(PLAN_HASH,recovered.turns.single().planHash); assertEquals(ACTION_HASH,store.list().single().turns.single().actionHash) }
        when(stage) {
            TurnStage.PLANNING -> { assertEquals(OutcomeCode.NEEDS_CONFIRMATION,coordinator.retryStage(loaded.id,"turn-v02",stage).code); assertEquals(PLAN_HASH,coordinator.publicView(loaded.id).turns.single().planHash); assertEquals(OutcomeCode.APPLIED,coordinator.confirmPlan(loaded.id,"turn-v02",PLAN_HASH).code) }
            TurnStage.NEEDS_CONFIRMATION -> { assertEquals(OutcomeCode.NEEDS_CONFIRMATION,coordinator.retryStage(loaded.id,"turn-v02",stage).code); assertEquals(OutcomeCode.INVALID_PLAN,coordinator.confirmPlan(loaded.id,"turn-v02","wrong").code); assertEquals(OutcomeCode.APPLIED,coordinator.confirmPlan(loaded.id,"turn-v02",PLAN_HASH).code) }
            else -> assertEquals(OutcomeCode.APPLIED,coordinator.retryStage(loaded.id,"turn-v02",stage).code)
        }
        val after=store.list().single(); val turn=after.turns.single()
        assertEquals(TurnStage.COMPLETE,turn.stage); assertEquals(PLAN_HASH,turn.planHash); assertEquals(ACTION_HASH,turn.actionHash)
        assertEquals(90,turn.result?.dice?.roll); assertEquals(5,after.state.hp); assertEquals(1,after.state.tick); assertEquals(1,after.state.revision); assertEquals(1,after.events.size)
        assertEquals(if(stage in setOf(TurnStage.RESOLVED,TurnStage.NARRATING))0 else 1,dice.consumed)
        assertEquals(if(stage==TurnStage.PLANNING)1 else 0,plans); assertEquals(1,narratives)
        assertEquals(OutcomeCode.ALREADY_APPLIED,coordinator.resolvePrepared(loaded.id,"turn-v02",0).code)
        assertEquals(1,store.list().single().events.size); assertEquals(after.state,RainyDock.replay(RainyDock.initialState(),after.events))
    }
    @Test fun planningSchema1Fixture()=verify(TurnStage.PLANNING)
    @Test fun confirmationSchema1Fixture()=verify(TurnStage.NEEDS_CONFIRMATION)
    @Test fun preparedSchema1Fixture()=verify(TurnStage.PREPARED)
    @Test fun resolvedSchema1Fixture()=verify(TurnStage.RESOLVED)
    @Test fun narratingSchema1Fixture()=verify(TurnStage.NARRATING)
}
