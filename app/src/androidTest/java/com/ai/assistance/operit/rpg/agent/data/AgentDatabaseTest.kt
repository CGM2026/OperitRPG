package com.ai.assistance.operit.rpg.agent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.initialWorld
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import com.ai.assistance.operit.rpg.agent.module.ExampleModules
import com.ai.assistance.operit.rpg.agent.engine.AgentCoordinator
import com.ai.assistance.operit.rpg.agent.engine.DemoTextModelPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentDatabaseTest {
 @Serializable private data class GoldRule(val id:String,val title:String,val quote:String,val q:String)
 @Serializable private data class GoldQuestion(val id:String,val language:String,val question:String,val expectedRuleIds:List<String>,val answerable:Boolean)
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private lateinit var db:AgentDatabase
 private lateinit var name:String
 private lateinit var store:RoomAgentStore
 private val module:ValidatedModule get() {
  val ref=SourceRef("module/sources/rules.md",sha256("检定使用百分骰"),"check","module_rule")
  val base=ValidatedModule(ModuleManifest(1,"test","1.0.0","测试","test","公开简介",1,"generic-d100-v1","1",emptyList()),"test-hash",
   ModuleWorld("test:room",listOf(CharacterTemplate("test:hero","旅人",mapOf("调查" to 60),mapOf("hp" to 6))),listOf(Location("test:room","房间","",emptyList())),listOf(NpcState("test:npc","船工","test:room","idle","谨慎","守约")),emptyList(),listOf(Fact("test:secret","暗门位置",Visibility(Audience.GM),true,ref)),emptyList(),emptyList(),GenerationPolicy(emptySet(),0,setOf("test:secret"))),
   ModuleRules("generic-d100-v1","1",setOf("调查"),mapOf("hp" to ResourceBounds(0,6)),mapOf("普通" to 0),setOf("time"),listOf(RuleEvidence("test:check",ref,"检定使用百分骰",Visibility(Audience.PUBLIC))),"分钟"),mapOf("module/sources/rules.md" to "检定使用百分骰"))
  val files=base.sources+mapOf("module/world.json" to encode(base.world),"module/rules.json" to encode(base.rules))
  val manifest=base.manifest.copy(files=files.toSortedMap().map{FileDigest(it.key,it.value.toByteArray().size.toLong(),sha256(it.value))})
  return base.copy(manifest=manifest,contentHash=sha256(StrictJson.json.encodeToString(manifest)),originalFiles=files)
 }
 @Before fun setup(){name="agent-test-${UUID.randomUUID()}.db";open();RoomModuleCatalog(db).install(module);store.createCampaign(campaign("a"));store.createCampaign(campaign("b"))}
 private fun open(){db=Room.databaseBuilder(context,AgentDatabase::class.java,name).build();store=RoomAgentStore(db)}
 private fun campaign(id:String)=Campaign(id,id,VersionRef("test","1.0.0",module.contentHash),VersionRef("generic-d100-v1","1",module.contentHash),0,0,initialWorld(module,"test:hero"),false,"local-profile",0)
 @After fun cleanup(){db.close();context.deleteDatabase(name)}
 @Test fun rollbackPendingUniquenessAndReopen(){
  try{store.transaction("a"){tx->tx.saveCampaign(tx.session.campaign.copy(revision=1));error("injected")}}catch(_:IllegalStateException){}
  assertEquals(0L,store.loadCampaign("a")!!.revision)
  val first=Turn("t1","a",1,"client","行动",0,TurnPhase.RECEIVED)
  store.transaction("a"){it.saveTurn(first)}
  var rejected=false
  try{store.transaction("a"){it.saveTurn(first.copy(id="t2",ordinal=2,clientMessageId="other"))}}catch(_:IllegalArgumentException){rejected=true}
  assertTrue(rejected);db.close();open()
  assertEquals(first,store.history("a",null,20).single())
 }
 @Test fun memoryPermissionsCorrectionsAndCampaignIsolation(){
  val memory=RoomMemoryRepository(db)
  memory.save(MemoryItem("public","a","船工承诺明早带路",listOf("test:npc"),0,0,Visibility(Audience.PUBLIC),"ACTIVE"))
  memory.save(MemoryItem("secret","a","船工暗门秘密",emptyList(),0,0,Visibility(Audience.NPC,"test:npc"),"ACTIVE"))
  for(i in 0 until 1000){
   val role=when(i%4){0->RoleContext("a",Audience.PUBLIC);1->RoleContext("a",Audience.NPC,"test:npc");2->RoleContext("a",Audience.NPC,"another");else->RoleContext("b",Audience.GM)}
   val found=memory.search(role,"暗门秘密",emptyList(),5)
   assertEquals(i%4==1,found.any{it.id=="secret"})
  }
  val replacement=memory.correct(RoleContext("a",Audience.PUBLIC),"public","船工改约后天带路")
  assertEquals("public",replacement.supersedesId)
  assertFalse(memory.search(RoleContext("a",Audience.PUBLIC),"明早",emptyList(),5).any{it.id=="public"})
  assertEquals("SUPERSEDED",db.dao().memory("a","public")!!.status)
 }
 @Test fun format2RoundTripPreservesDependenciesAndRequiresModelRebind(){
  RoomMemoryRepository(db).save(MemoryItem("note","a","约定",emptyList(),0,0,Visibility(Audience.PUBLIC),"ACTIVE"))
  val corrected=RoomMemoryRepository(db).correct(RoleContext("a",Audience.PUBLIC),"note","修订约定")
  val docs=ReferenceDocumentService(db);val document=docs.importText("旅行补充","绳索桥边的守则",Visibility(Audience.PUBLIC));docs.attach("a",document)
  val service=AgentBackupService(db,context.cacheDir);val out=ByteArrayOutputStream();service.export("a",out)
  assertEquals("a",service.preview(ByteArrayInputStream(out.toByteArray())).title)
  val restored=service.importBackup(ByteArrayInputStream(out.toByteArray()))
  assertNotEquals("a",restored.campaignId)
  val c=store.loadCampaign(restored.campaignId)!!
  assertEquals("",c.modelFingerprint);assertEquals(store.loadCampaign("a")!!.world,c.world)
  val notes=RoomMemoryRepository(db);val restoredNote=notes.recent(RoleContext(c.id,Audience.PUBLIC),5).single()
  assertEquals("修订约定",restoredNote.text)
  assertEquals(listOf("修订约定","约定"),notes.correctionHistory(RoleContext(c.id,Audience.PUBLIC),restoredNote.id).map{it.text})
  assertEquals(1,db.dao().corrections(c.id,0,100).size)
  assertEquals("绳索桥边的守则",ReferenceDocumentService(db).read(db.dao().attachedDocuments(c.id,0,100).single().id))
  assertEquals(module,RoomModuleCatalog(db).get(c.module))
 }
 @Test fun longHistoryIsPagedAndBadArchiveLeavesNoCampaign(){
  for(i in 1L..10_000L)store.transaction("a"){it.saveCampaign(it.session.campaign.copy(nextTurnOrdinal=i+1));it.saveTurn(Turn("history-$i","a",i,"message-$i","问答",0,TurnPhase.COMPLETE))}
  assertEquals(50,store.history("a",null,50).size)
  assertEquals(9950L,store.history("a",9951,50).first().ordinal)
  val service=AgentBackupService(db,context.cacheDir);val out=ByteArrayOutputStream();service.export("a",out)
  val ref=service.importBackup(ByteArrayInputStream(out.toByteArray()))
  assertEquals(10_000L,store.history(ref.campaignId,null,1).single().ordinal)
  val before=store.listCampaigns(0,100).size
  try{service.importBackup(ByteArrayInputStream(byteArrayOf(1,2,3)));fail("bad archive accepted")}catch(_:IllegalArgumentException){}
  assertEquals(before,store.listCampaigns(0,100).size)
 }
 @Test fun hundredRuleGoldQueriesUseRealRoomFtsAndVersionPermissions(){
  val assets=InstrumentationRegistry.getInstrumentation().context.assets
  val corpus=assets.open("rpg-agent/rules-corpus.json").bufferedReader().use{AgentJson.decodeFromString<List<GoldRule>>(it.readText())}
  val questions=assets.open("rpg-agent/rules-gold.json").bufferedReader().use{AgentJson.decodeFromString<List<GoldQuestion>>(it.readText())}
  assertEquals(100,questions.size);assertTrue(questions.count{it.language=="zh"}>=70)
  val source=corpus.joinToString("\n"){it.quote};val sourceId="module/sources/rules.md"
  val base=module.copy(manifest=module.manifest.copy(version="2.0.0"),sources=mapOf(sourceId to source),rules=module.rules.copy(evidence=corpus.map{RuleEvidence(it.id,SourceRef(sourceId,sha256(source),it.id,"module_rule"),it.quote,Visibility(Audience.PUBLIC))}))
  val files=base.sources+mapOf("module/world.json" to encode(base.world),"module/rules.json" to encode(base.rules))
  val manifest=base.manifest.copy(files=files.toSortedMap().map{FileDigest(it.key,it.value.toByteArray().size.toLong(),sha256(it.value))})
  val m=base.copy(manifest=manifest,contentHash=sha256(StrictJson.json.encodeToString(manifest)),originalFiles=files)
  RoomModuleCatalog(db).install(m)
  val c=campaign("gold").copy(module=VersionRef("test","2.0.0",m.contentHash),rules=VersionRef("generic-d100-v1","1",m.contentHash))
  store.createCampaign(c)
  val repository=RoomRuleRepository(db);var found=0;var answerable=0
  questions.forEach{q->val hits=repository.search(RoleContext(c.id,Audience.PUBLIC),c.module,q.question,5)
   if(q.answerable){answerable++;if(hits.any{it.ruleId in q.expectedRuleIds})found++}else assertTrue("${q.id} invented evidence",hits.isEmpty())
   hits.forEach{e->assertEquals(e,repository.read(RoleContext(c.id,Audience.PUBLIC),c.module,e.source.sourceId,e.source.anchor))}
  }
  assertTrue("Recall@5=$found/$answerable",found.toDouble()/answerable>=0.90)
  // Installing version 2 cannot change an already bound version 1 campaign.
  assertEquals("检定使用百分骰",repository.search(RoleContext("a",Audience.PUBLIC),campaign("a").module,"test:check",5).first().quote)
 }
 @Test fun publicQuoteDoesNotPublishOtherTextInTheSameSource(){
  val original=module;val text="检定使用百分骰\n主持秘密：紫晶暗号藏在王座。";val sourceId="module/sources/rules.md"
  val evidence=original.rules.evidence.single().copy(source=original.rules.evidence.single().source.copy(sourceHash=sha256(text)))
  val base=original.copy(manifest=original.manifest.copy(version="3.0.0"),sources=mapOf(sourceId to text),rules=original.rules.copy(evidence=listOf(evidence)))
  val files=base.sources+mapOf("module/world.json" to encode(base.world),"module/rules.json" to encode(base.rules))
  val manifest=base.manifest.copy(files=files.toSortedMap().map{FileDigest(it.key,it.value.toByteArray().size.toLong(),sha256(it.value))})
  val m=base.copy(manifest=manifest,contentHash=sha256(StrictJson.json.encodeToString(manifest)),originalFiles=files)
  RoomModuleCatalog(db).install(m)
  val c=campaign("mixed").copy(module=VersionRef("test","3.0.0",m.contentHash),rules=VersionRef("generic-d100-v1","1",m.contentHash));store.createCampaign(c)
  val repo=RoomRuleRepository(db)
  assertTrue(repo.search(RoleContext(c.id,Audience.PUBLIC),c.module,"紫晶暗号",5).isEmpty())
  assertNull(repo.read(RoleContext(c.id,Audience.PUBLIC),c.module,sourceId,"offset:0"))
  assertTrue(repo.search(RoleContext(c.id,Audience.GM),c.module,"紫晶暗号",5).any{it.quote.contains("紫晶暗号")})
  assertEquals(evidence,repo.search(RoleContext(c.id,Audience.PUBLIC),c.module,"test:check",5).single())
 }
 @Test fun conversationMemorySurvivesReopenCorrectionAndBackupWithExactTurnSource(){
  val originalWorld=store.loadCampaign("a")!!.world
  val player="我叫青禾，答应船工明天归还罗盘。"+"长文".repeat(5000)
  val narration="船工回应：我记住你的承诺了。"+"回应".repeat(6000)
  val turn=Turn("conversation-turn","a",1,"conversation-client",player,0,TurnPhase.COMPLETE,narration=narration)
  store.transaction("a"){tx->tx.saveCampaign(tx.session.campaign.copy(nextTurnOrdinal=2));tx.saveTurn(turn)}
  db.close();open()
  val memory=RoomMemoryRepository(db)
  val item=memory.search(RoleContext("a",Audience.PUBLIC),"青禾罗盘",emptyList(),5).single()
  assertEquals("CONVERSATION",item.kind);assertEquals(turn.id,item.sourceTurnId)
  assertTrue(item.text.contains("对话记录，不代表世界状态"));assertTrue(item.text.length<=12000)
  assertEquals(player,store.loadTurn("a",item.sourceTurnId!!)!!.playerText)
  assertEquals(narration,store.loadTurn("a",item.sourceTurnId!!)!!.narration)
  val replacement=memory.correct(RoleContext("a",Audience.PUBLIC),item.id,"玩家更正：名字是青禾，归还日期是后天。（对话记录，不代表世界状态）")
  assertEquals(turn.id,replacement.sourceTurnId);assertEquals("CONVERSATION",replacement.kind)
  val out=ByteArrayOutputStream();val service=AgentBackupService(db,context.cacheDir);service.export("a",out)
  val restored=service.importBackup(ByteArrayInputStream(out.toByteArray()))
  val restoredNote=memory.search(RoleContext(restored.campaignId,Audience.PUBLIC),"青禾后天",emptyList(),5).single()
  assertEquals("CONVERSATION",restoredNote.kind);assertNotEquals(turn.id,restoredNote.sourceTurnId)
  val source=store.loadTurn(restored.campaignId,restoredNote.sourceTurnId!!)!!
  assertEquals(player,source.playerText);assertEquals(narration,source.narration)
  assertEquals(2,memory.correctionHistory(RoleContext(restored.campaignId,Audience.PUBLIC),restoredNote.id).size)
  assertEquals(originalWorld,store.loadCampaign("a")!!.world)
  assertEquals(originalWorld,store.loadCampaign(restored.campaignId)!!.world)
  assertTrue(memory.search(RoleContext("b",Audience.PUBLIC),"青禾",emptyList(),5).isEmpty())
 }
 @Test fun realTwelveNpcModuleTwoActionsReopenAndBackupIgnoreOnlyCollectionOrder()=runBlocking {
  val m=ExampleModules.all().first();assertEquals(12,m.world.npcs.size)
  val catalog=RoomModuleCatalog(db);catalog.install(m)
  val engine=AgentCoordinator(store,catalog,RoomRuleRepository(db),RoomMemoryRepository(db),DemoTextModelPort(m),DiceSource{1},Clock{0})
  val ref=VersionRef(m.manifest.moduleId,m.manifest.version,m.contentHash)
  engine.create("twelve","真实示例备份",ref,m.world.characters.first().id,false,"demo")
  assertEquals(TurnPhase.COMPLETE,engine.submit("twelve","wait","等待").phase)
  assertEquals(TurnPhase.COMPLETE,engine.submit("twelve","talk","交谈${m.world.npcs.first().name}").phase)
  val before=store.loadCampaign("twelve")!!;assertEquals(2L,before.lastEventSeq)
  // Deliberately vary persistence order. No field or entity identity changes.
  store.transaction("twelve"){tx->val c=tx.session.campaign;tx.saveCampaign(c.copy(world=c.world.copy(npcs=c.world.npcs.reversed(),facts=c.world.facts.reversed(),items=c.world.items.reversed(),objectives=c.world.objectives.reversed())))}
  db.close();open()
  val reopened=store.loadCampaign("twelve")!!
  assertEquals(canonicalWorld(before.world),canonicalWorld(reopened.world))
  val service=AgentBackupService(db,context.cacheDir);val out=ByteArrayOutputStream();service.export("twelve",out)
  val restored=service.importBackup(ByteArrayInputStream(out.toByteArray()))
  db.close();open()
  val after=store.loadCampaign(restored.campaignId)!!
  assertEquals(12,after.world.npcs.size);assertEquals(canonicalWorld(before.world),canonicalWorld(after.world))
  assertEquals(2,store.events(after.id,0,100).size)
  assertEquals(2,store.history(after.id,null,100).size)
  assertEquals(5,after.world.npcs.single{it.id==m.world.npcs.first().id}.relationships["trust"])
 }
}
