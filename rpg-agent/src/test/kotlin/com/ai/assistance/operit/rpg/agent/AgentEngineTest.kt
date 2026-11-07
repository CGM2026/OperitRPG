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
    private fun fixture(model:TextModelPort=DemoTextModelPort(m),store:TestStore=TestStore(),narrator:TextModelPort=model):Pair<AgentCoordinator,TestStore> {
        val a=AgentCoordinator(store,TestCatalog(m),TestRules(m),TestMemories(),model,narrator,DiceSource { _ -> 12 },Clock { 1000 })
        a.create("c","长团",VersionRef(m.manifest.moduleId,m.manifest.version,m.contentHash),m.world.characters.first().id,true,"demo","demo")
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
    private class RecordingPort(private val inner:TextModelPort):TextModelPort {
        val packets=mutableListOf<ContextPacket>()
        override fun cancel(attemptId:String)=inner.cancel(attemptId)
        override suspend fun generate(attemptId:String,packet:ContextPacket):TextModelReply { packets+=packet; return inner.generate(attemptId,packet) }
    }
    @Test fun plannerAndNarratorPortsAreSeparateAndRebindIndependently()=runBlocking {
        val planner=RecordingPort(DemoTextModelPort(m)); val narrator=RecordingPort(DemoTextModelPort(m))
        val (a,s)=fixture(planner,narrator=narrator)
        val first=a.submit("c","m1","等待")
        assertTrue(planner.packets.isNotEmpty() && planner.packets.all { it.purpose=="planner" })
        assertTrue(narrator.packets.isNotEmpty() && narrator.packets.all { it.purpose=="narrator" })
        assertEquals(setOf("demo"),first.attempts.map { it.fingerprint }.toSet())
        a.rebindNarrator("c","narrator-2")
        assertEquals("demo",s.campaigns.getValue("c").modelFingerprint); assertEquals("narrator-2",s.campaigns.getValue("c").narratorFingerprint)
        val second=a.submit("c","m2","等待")
        assertEquals(setOf("demo"),second.attempts.filter { it.purpose=="planner" }.map { it.fingerprint }.toSet())
        assertEquals(setOf("narrator-2"),second.attempts.filter { it.purpose=="narrator" }.map { it.fingerprint }.toSet())
        a.rebindPlanner("c","planner-2")
        assertEquals("planner-2",s.campaigns.getValue("c").modelFingerprint); assertEquals("narrator-2",s.campaigns.getValue("c").narratorFingerprint)
        assertFails { a.rebindNarrator("c","") }; assertFails { a.rebindPlanner("c"," ") }
    }
    @Test fun sceneHoldAppliesToExactlyOneNarrationAndLevelsAreClamped()=runBlocking {
        val leveled=m.copy(rules=m.rules.copy(contentLevels=listOf(ContentLevel("低","GUIDE_LOW"),ContentLevel("高","GUIDE_HIGH")),defaultContentLevel=0))
        val narrator=RecordingPort(DemoTextModelPort(leveled)); val store=TestStore()
        val a=AgentCoordinator(store,TestCatalog(leveled),TestRules(leveled),TestMemories(),DemoTextModelPort(leveled),narrator,DiceSource { _ -> 12 },Clock { 1000 })
        a.create("c","长团",VersionRef(leveled.manifest.moduleId,leveled.manifest.version,leveled.contentHash),leveled.world.characters.first().id,true,"demo","demo")
        assertEquals(0,store.campaigns.getValue("c").contentLevel)
        a.holdScene("c"); assertTrue(store.campaigns.getValue("c").sceneHold)
        a.submit("c","m1","等待")
        assertTrue(narrator.packets.last().system.contains(ContextAssembler.SCENE_HOLD)); assertTrue(narrator.packets.last().system.contains("GUIDE_LOW"))
        assertFalse(store.campaigns.getValue("c").sceneHold)
        a.submit("c","m2","等待")
        assertFalse(narrator.packets.last().system.contains(ContextAssembler.SCENE_HOLD))
        assertEquals(1,a.adjustContentLevel("c",1).contentLevel); assertEquals(1,a.adjustContentLevel("c",1).contentLevel)
        a.submit("c","m3","等待"); assertTrue(narrator.packets.last().system.contains("GUIDE_HIGH"))
        assertEquals(0,a.adjustContentLevel("c",-1).contentLevel); assertEquals(0,a.adjustContentLevel("c",-1).contentLevel)
    }
    @Test fun tableCommandsRequireDeclaredContentLevels()=runBlocking {
        val (a,_)=fixture()
        assertFails { a.holdScene("c") }; assertFails { a.adjustContentLevel("c",1) }
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
    @Test fun planningContextRemainsLocalWithTenThousandRemoteNpcsAndFacts() {
        val (_,s)=fixture(); val c=s.campaigns.getValue("c"); val remote=m.world.locations.last().id
        val expanded=c.copy(world=c.world.copy(npcs=c.world.npcs+List(10000) { NpcState("remote-$it","离场人物$it",remote,"idle","PRIVATE_PERSONALITY_$it","PRIVATE_GOAL_$it") },facts=c.world.facts+List(10000) { Fact("remote-fact-$it","UNRELATED_PRIVATE_FACT_$it",Visibility(Audience.GM),false,m.world.facts.first().source) }))
        val turn=Turn("t","c",1,"m","调查",0,TurnPhase.GATHERING)
        val context=ContextAssembler(TestMemories()).planning(expanded,m,turn)
        assertTrue(context.input.length<12000); assertFalse(context.input.contains("UNRELATED_PRIVATE_FACT")); assertFalse(context.input.contains("PRIVATE_GOAL")); assertTrue(context.input.contains("availableActions")); assertTrue(context.input.contains(m.world.actions.first().id))
    }
    @Test fun narratorDoesNotReceivePrivateEvidenceEvenFromPersistedResult() {
        val (_,s)=fixture(); val c=s.campaigns.getValue("c")
        val privateRule=m.rules.evidence.first().copy(quote="GM_ONLY_RULE_SECRET",visibility=Visibility(Audience.GM))
        val result=CommittedResult("t",1,true,null,"公开结果",listOf(privateRule))
        val t=Turn("t","c",1,"m","调查",0,TurnPhase.COMMITTED,result=result)
        val packet=ContextAssembler(TestMemories()).narration(c,m,t)
        assertFalse(packet.input.contains("GM_ONLY_RULE_SECRET")); assertFalse(packet.input.contains(m.world.facts.last().text))
    }
    @Test fun bothModulesCompleteThreeChaptersThroughRecordedActions() {
        ExampleModules.all().forEach { module ->
            val adapter=GenericD100(); val ref=VersionRef(module.manifest.moduleId,module.manifest.version,module.contentHash)
            var c=Campaign("c","chapters",ref,VersionRef(module.rules.adapterId,module.rules.adapterVersion,module.contentHash),0,0,initialWorld(module,module.world.characters.first().id),false,"demo","demo",0)
            val recorded=mutableListOf<WorldEvent>()
            fun execute(p:ActionProposal) { val plan=freeze(c,p,module.rules.evidence); val (event,_)=adapter.resolve(c,plan,module,"turn-${c.revision}",DiceSource { _ -> 1 }); c=c.copy(world=adapter.applyRecordedEvent(c.world,event,module),revision=c.revision+1,lastEventSeq=event.seq); recorded+=event }
            module.world.locations.forEachIndexed { i,l ->
                if(i>0) { val e=module.rules.evidence.first { it.ruleId.endsWith(":travel") }; execute(ActionProposal("move",listOf(l.id),null,"normal",listOf(e.ruleId),emptyList(),listOf(Effect("move",l.id,source=e.source),Effect("time","world",amount=10,source=e.source)),emptyList(),publicSummary="移动")) }
                val a=adapter.availableActions(c,module).single { it.targetIds==listOf(l.id) }
                execute(ActionProposal(a.actionType,a.targetIds,a.skill,a.difficulty,listOf(a.ruleId),a.conditions,a.successEffects,a.failureEffects,publicSummary=a.title))
            }
            assertTrue(c.world.objectives.all { it.status=="complete" }); assertTrue(c.world.facts.all { it.visibility.audience==Audience.PUBLIC }); assertEquals(11,recorded.size)
            val replayed=recorded.fold(initialWorld(module,module.world.characters.first().id)) { world,event -> adapter.applyRecordedEvent(world,event,module) }
            assertEquals(c.world,replayed)
        }
    }
    @Test fun conversationMemoryCarriesTurnSourceWithoutBecomingWorldFact() {
        val (_,s)=fixture(); val c=s.campaigns.getValue("c"); val repo=TestMemories()
        val note=MemoryItem("conversation-1","c","玩家说：我已拿到万能钥匙",emptyList(),0,0,Visibility(Audience.PUBLIC),"ACTIVE",sourceTurnId="turn-1",kind="CONVERSATION")
        repo.save(note)
        assertEquals(note,StrictJson.decode<MemoryItem>(StrictJson.json.encodeToString(note)))
        val old=StrictJson.decode<MemoryItem>("""{"id":"old","campaignId":"c","text":"旧记录","entityIds":[],"fromSeq":0,"toSeq":0,"visibility":{"audience":"PUBLIC"},"status":"ACTIVE"}""")
        assertNull(old.sourceTurnId); assertEquals("EVENT",old.kind)
        val packet=ContextAssembler(repo).planning(c,m,Turn("t","c",1,"msg","使用万能钥匙",0,TurnPhase.GATHERING))
        assertTrue(packet.system.contains("不证明发言内容是真实世界事实")); assertTrue(packet.input.contains("CONVERSATION")); assertTrue(packet.input.contains("turn-1")); assertFalse(c.world.items.any { it.title=="万能钥匙" })
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
