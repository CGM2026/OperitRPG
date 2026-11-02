package com.ai.assistance.operit.rpg.agent

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.engine.*
import com.ai.assistance.operit.rpg.agent.memory.*
import com.ai.assistance.operit.rpg.agent.module.*
import com.ai.assistance.operit.rpg.agent.rules.*
import com.ai.assistance.operit.rpg.agent.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class TestStore:AgentStore {
    val campaigns=mutableMapOf<String,Campaign>(); val turns=mutableMapOf<String,Turn>(); val log=mutableListOf<WorldEvent>(); var failBoundary:String?=null; var historyReads=0
    override fun createCampaign(campaign:Campaign) { require(campaigns.putIfAbsent(campaign.id,campaign)==null) }
    override fun loadCampaign(campaignId:String)=campaigns[campaignId]
    override fun loadTurn(campaignId:String,turnId:String)=turns[turnId]?.takeIf { it.campaignId==campaignId }
    override fun findTurnByClientMessage(campaignId:String,clientMessageId:String)=turns.values.find { it.campaignId==campaignId && it.clientMessageId==clientMessageId }
    @Synchronized override fun <T> transaction(campaignId:String,block:(AgentTransaction)->T):T {
        val before=campaigns.toMap(); val oldTurns=turns.toMap(); val oldEvents=log.toList()
        try {
            val c=campaigns.getValue(campaignId)
            val pending=turns.values.filter { it.campaignId==campaignId && it.phase !in setOf(TurnPhase.CANCELLED,TurnPhase.COMPLETE) }.singleOrNull()
            return block(object:AgentTransaction {
                override val session=CampaignSession(c,pending)
                override fun saveCampaign(campaign:Campaign) { campaigns[campaign.id]=campaign; if(failBoundary=="campaign")error("FAULT") }
                override fun saveTurn(turn:Turn) { require(turns.values.none { it.id!=turn.id && it.campaignId==turn.campaignId && (it.ordinal==turn.ordinal || it.clientMessageId==turn.clientMessageId) }); turns[turn.id]=turn; if(failBoundary=="turn")error("FAULT") }
                override fun appendEvent(event:WorldEvent) { require(log.none { it.campaignId==event.campaignId && (it.seq==event.seq || it.turnId==event.turnId) }); log+=event; if(failBoundary=="event")error("FAULT") }
            })
        } catch(e:Throwable) { campaigns.clear(); campaigns.putAll(before); turns.clear(); turns.putAll(oldTurns); log.clear(); log.addAll(oldEvents); throw e }
    }
    override fun listCampaigns(offset:Int,limit:Int)=campaigns.values.drop(offset).take(limit).map { CampaignHeader(CampaignRef(LONGFORM_ENGINE,it.id),it.title,it.updatedAt) }
    override fun history(campaignId:String,beforeOrdinal:Long?,limit:Int):List<Turn> { historyReads++; return turns.values.filter { it.campaignId==campaignId && (beforeOrdinal==null || it.ordinal<beforeOrdinal) }.sortedByDescending { it.ordinal }.take(limit) }
    override fun events(campaignId:String,afterSeq:Long,limit:Int)=log.filter { it.campaignId==campaignId && it.seq>afterSeq }.take(limit)
}
class TestCatalog(val m:ValidatedModule):ModuleCatalog { override fun get(ref:VersionRef)=m.also { require(ref.id==m.manifest.moduleId && ref.hash==m.contentHash) }; override fun list()=listOf(m); override fun install(module:ValidatedModule)=Unit }
class TestRules(val m:ValidatedModule):RuleRepository {
    override fun search(role:RoleContext,module:VersionRef,query:String,limit:Int)=m.rules.evidence.filter { canRead(role,it.visibility) }.take(limit)
    override fun read(role:RoleContext,module:VersionRef,sourceId:String,anchor:String)=m.rules.evidence.find { it.source.sourceId==sourceId && it.source.anchor==anchor && canRead(role,it.visibility) }
}
class TestMemories:MemoryRepository {
    val notes=mutableListOf<MemoryItem>()
    override fun save(item:MemoryItem) { notes+=item }
    override fun search(role:RoleContext,query:String,entityIds:List<String>,limit:Int)=notes.filter { it.campaignId==role.campaignId && canRead(role,it.visibility) && it.status=="ACTIVE" && (it.text.contains(query) || entityIds.any { id -> id in it.entityIds }) }.take(limit)
    override fun recent(role:RoleContext,limit:Int)=notes.filter { it.campaignId==role.campaignId && canRead(role,it.visibility) && it.status=="ACTIVE" }.takeLast(limit)
}
class AgentEngineTest {
    private val m=ExampleModules.all().first()
    private fun fixture(model:TextModelPort=DemoTextModelPort(m),store:TestStore=TestStore()):Pair<AgentCoordinator,TestStore> {
        val a=AgentCoordinator(store,TestCatalog(m),TestRules(m),TestMemories(),model,DiceSource { 12 },Clock { 1000 })
        a.create("c","长团",VersionRef(m.manifest.moduleId,m.manifest.version,m.contentHash),m.world.characters.first().id,true,"demo")
        return a to store
    }
    @Test fun examplesRoundTripAndAreIndependent() {
        ExampleModules.all().forEach { module ->
            assertTrue(module.world.locations.size>=6); assertTrue(module.world.npcs.size>=12); assertEquals(3,module.world.objectives.map { it.chapter }.distinct().size)
            val bytes=ByteArrayOutputStream().also { ExampleModules.writeZip(module,it) }.toByteArray()
            val restored=ModuleZipImporter().validate(bytes.inputStream()); assertEquals(module,restored)
        }
        assertTrue(ExampleModules.all().map { it.manifest.moduleId }.distinct().size==2)
    }
    @Test fun strictProtocolRejectsDuplicatesUnknownAndMixedFields() {
        listOf("{\"version\":1,\"version\":1,\"kind\":\"answer\",\"text\":\"x\"}","{\"version\":1,\"kind\":\"answer\",\"text\":\"x\",\"calls\":[]}","{\"version\":1,\"kind\":\"shell\",\"text\":\"x\"}","{\"version\":1,\"kind\":\"read_tools\",\"calls\":[{\"invocationId\":\"a\",\"name\":\"rules.search\",\"arguments\":{\"query\":\"a\",\"query\":\"b\"}}]}").forEach { assertFails { AgentProtocol.parse(it) } }
        assertEquals("answer",AgentProtocol.parse("{\"version\":1,\"kind\":\"answer\",\"text\":\"x\"}").kind)
    }
    @Test fun multiStepToolsCommitExactlyOnceAndNoHistoryAggregate()=runBlocking {
        val (a,s)=fixture(); val t=a.submit("c","msg","调查当前地点")
        assertNull(t.error); assertEquals(TurnPhase.COMPLETE,t.phase); assertEquals(2,t.tools.size); assertEquals(1,s.log.size)
        assertEquals(12,t.result?.dice?.value); assertEquals(1,s.campaigns.getValue("c").revision); assertEquals(0,s.historyReads)
        assertEquals(t,a.submit("c","msg","重复消息")); assertEquals(1,s.log.size)
        assertEquals(Audience.PUBLIC,s.campaigns.getValue("c").world.facts.first().visibility.audience)
    }
    @Test fun interruptedNarrationNeverRollsAgain()=runBlocking {
        var fail=true; val demo=DemoTextModelPort(m)
        val port=object:TextModelPort { override fun cancel(attemptId:String)=Unit; override suspend fun generate(attemptId:String,packet:ContextPacket):TextModelReply { if(packet.purpose=="narrator" && fail)error("OFFLINE"); return demo.generate(attemptId,packet) } }
        val (a,s)=fixture(port); val t=a.submit("c","msg","调查")
        assertEquals(TurnPhase.NARRATING,t.phase); assertEquals(1,s.log.size); val roll=t.result?.dice
        a.interrupt("c"); fail=false
        val resumed=a.resume("c",t.id); assertEquals(TurnPhase.COMPLETE,resumed.phase); assertEquals(roll,resumed.result?.dice); assertEquals(1,s.log.size)
    }
    @Test fun unknownToolAndDuplicateQueryStopBeforeCommit()=runBlocking {
        val port=object:TextModelPort { override fun cancel(attemptId:String)=Unit; override suspend fun generate(attemptId:String,packet:ContextPacket)=TextModelReply("{\"version\":1,\"kind\":\"read_tools\",\"calls\":[{\"invocationId\":\"x\",\"name\":\"shell\",\"arguments\":{}}]}",Usage(1,1)) }
        val (a,s)=fixture(port); val t=a.submit("c","msg","调查"); assertEquals("UNKNOWN_TOOL",t.error); assertTrue(s.log.isEmpty()); assertEquals(0,s.campaigns.getValue("c").revision)
    }
    @Test fun oneThousandRoleCampaignVisibilityCombinations() {
        val audiences=Audience.entries
        repeat(1000) { i ->
            val role=RoleContext("c${i%7}",audiences[i%4],if(i%4>=2)"s${i%3}" else null)
            val v=Visibility(audiences[(i/4)%4],if((i/4)%4>=2)"s${(i/7)%3}" else null)
            val expected=role.audience==Audience.GM || v.audience==Audience.PUBLIC || (role.audience==v.audience && role.subjectId==v.subjectId)
            assertEquals(expected,canRead(role,v))
            val repo=TestMemories(); repo.save(MemoryItem("$i","other","TOP_SECRET",emptyList(),1,1,v,"ACTIVE")); assertTrue(repo.search(role,"TOP_SECRET",emptyList()).isEmpty())
        }
    }
    @Test fun atomicTransactionFaultsLeaveZeroOrOneEvent()=runBlocking {
        listOf("event","campaign","turn").forEach { boundary ->
            val (a,s)=fixture(); val demo=DemoTextModelPort(m)
            // Inject only after planning, exactly at the transactional world settlement boundary.
            val t=a.submit("c","prepare","调查"); assertEquals(TurnPhase.COMPLETE,t.phase)
            val committed=s.log.single(); val c=s.campaigns.getValue("c")
            s.failBoundary=boundary
            assertFails { s.transaction("c") { tx -> tx.appendEvent(committed.copy(seq=2,turnId="next")); tx.saveCampaign(c.copy(revision=2)); tx.saveTurn(t.copy(id="next",ordinal=2,clientMessageId="next")) } }
            assertEquals(1,s.log.size); assertEquals(1,s.campaigns.getValue("c").revision)
        }
    }
    @Test fun actualSettlementFailureRollsBackEventProjectionAndResult()=runBlocking {
        listOf("event","campaign","turn").forEach { boundary ->
            val s=TestStore(); val demo=DemoTextModelPort(m)
            val port=object:TextModelPort { override fun cancel(attemptId:String)=Unit; override suspend fun generate(attemptId:String,packet:ContextPacket):TextModelReply { val reply=demo.generate(attemptId,packet); if(reply.text.contains("\"kind\":\"propose_action\""))s.failBoundary=boundary; return reply } }
            val (a,_)=fixture(port,s)
            runCatching { a.submit("c","msg","调查") }
            assertTrue(s.log.isEmpty()); assertEquals(0,s.campaigns.getValue("c").revision); assertEquals(m.world.facts,s.campaigns.getValue("c").world.facts)
            s.failBoundary=null
        }
    }
    @Test fun lateModelResponseAfterCancelCannotMutateWorld()=runBlocking {
        val entered=CompletableDeferred<Unit>(); val reply=CompletableDeferred<Unit>()
        val port=object:TextModelPort { override fun cancel(attemptId:String) { reply.complete(Unit) }; override suspend fun generate(attemptId:String,packet:ContextPacket):TextModelReply { entered.complete(Unit); reply.await(); return TextModelReply("{\"version\":1,\"kind\":\"answer\",\"text\":\"迟到回答\"}",Usage(1,1)) } }
        val (a,s)=fixture(port); val task=async { a.submit("c","msg","调查") }; entered.await(); val pending=s.turns.values.single(); a.cancel("c",pending.id); val t=task.await()
        assertEquals(TurnPhase.CANCELLED,t.phase); assertNull(t.narration); assertTrue(s.log.isEmpty())
    }
    @Test fun twoHundredCrossChapterFactsPromisesAndCorrectionsStayTraceable() {
        val notes=TestMemories()
        repeat(200) { i ->
            val text=when(i%3) { 0 -> "第${i/70+1}章人物${i}承诺在冬至带来药包"; 1 -> "人物${i}曾在旧信塔发现足迹"; else -> "人物${i}表示边界水路已恢复" }
            val item=MemoryItem("note-$i","c",text,listOf("npc-$i"),i+1L,i+1L,Visibility(Audience.PUBLIC),"ACTIVE")
            if(i%10==0) { notes.save(item.copy(status="SUPERSEDED")); notes.save(item.copy(id="correct-$i",text="人物${i}更正：约定改为春分",supersedesId=item.id)) } else notes.save(item)
        }
        repeat(200) { i -> val found=notes.search(RoleContext("c",Audience.PUBLIC),"npc-$i",listOf("npc-$i"),5); assertEquals(1,found.size); assertEquals(i+1L,found.single().fromSeq); if(i%10==0) { assertTrue(found.single().text.contains("春分")); assertEquals("note-$i",found.single().supersedesId) } }
    }
    @Test fun lockedTruthNpcPresenceAndGenerationPolicyAreEnforced() {
        val (a,s)=fixture(); val c=s.campaigns.getValue("c"); val rule=m.rules.evidence.last(); val source=rule.source
        fun proposal(e:Effect)=ActionProposal("investigate",listOf(c.world.locationId),"调查","normal",listOf(rule.ruleId),emptyList(),listOf(e),emptyList(),publicSummary="调查")
        assertFails { GenericD100().validateProposal(c,m,proposal(Effect("reveal_fact",m.world.facts.last().id,source=source)),m.rules.evidence) }
        assertFails { GenericD100().validateProposal(c,m,proposal(Effect("npc_status",m.world.npcs.last().id,value="helpful",source=source)),m.rules.evidence) }
        assertFails { GenericD100().validateProposal(c.copy(allowGeneration=false),m,proposal(Effect("time","world",amount=1,source=source)).copy(creations=listOf(EntityCreation("npc","winter-post:new","新人物",c.world.locationId,"当地人",source.copy(authority="generated")))),m.rules.evidence) }
        val hidden=ToolCall("x","world.inspect",buildJsonObject { putJsonArray("entityIds") { add(m.world.facts.first().id) } })
        assertFails { ToolRegistry(TestRules(m),TestMemories()).execute(hidden,c,m,RoleContext("c",Audience.PUBLIC)) }
        val npcPacket=ContextAssembler(TestMemories()).npc(c.copy(world=c.world.copy(facts=c.world.facts.map { it.copy(visibility=Visibility(Audience.PUBLIC)) })),c.world.npcs.first().id)
        assertFalse(npcPacket.input.contains(m.world.facts.first().text))
    }
    @Test fun tenThousandRecordedTurnsReplayWithoutModelsOrRerolls() {
        val rules=GenericD100(); var w=initialWorld(m,m.world.characters.first().id); val source=m.rules.evidence.first().source
        repeat(10000) { i ->
            val event=WorldEvent(1,"c",i+1L,"t$i",listOf(Effect("time","world",amount=1,source=source)),emptyList(),DiceRoll(100,(i%100)+1,50),Visibility(Audience.PUBLIC),listOf(source))
            w=rules.applyRecordedEvent(w,event,m)
        }
        assertEquals(10000,w.gameTime); assertEquals(m.world.npcs,w.npcs); assertEquals(m.world.facts,w.facts)
    }
    @Test fun zipRejectsTraversalDuplicateAndUnknownFiles() {
        fun zip(names:List<String>)=ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write("{}".toByteArray()); z.closeEntry() } } }.toByteArray()
        listOf("../manifest.json","/module/manifest.json","module/evil.js","module/sources/../evil.md","module\\manifest.json").forEach { assertFails { ModuleZipImporter().validate(zip(listOf(it)).inputStream()) } }
    }
    @Test fun generateDistributionPacks() {
        val directory=File("../examples/rpg-agent-v03"); directory.mkdirs()
        ExampleModules.all().forEach { module -> File(directory,"${module.manifest.moduleId}.rpgmodule.zip").outputStream().use { ExampleModules.writeZip(module,it) }; File(directory,"${module.manifest.moduleId}.draft.json").writeText(StrictJson.json.encodeToString(ModuleDraft(module.manifest.moduleId,module.manifest.version,module.manifest.title,module.manifest.author,module.manifest.description,module.world,module.rules,module.sources))) }
    }
}
