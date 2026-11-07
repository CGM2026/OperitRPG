package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.RuleAdapters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.Callable

internal val AgentJson=Json { encodeDefaults=true; ignoreUnknownKeys=false }
internal inline fun <reified T> encode(value:T)=AgentJson.encodeToString(value)
internal inline fun <reified T> decode(value:String):T=AgentJson.decodeFromString(value)
internal fun page(limit:Int):Int { require(limit in 1..500); return limit }
internal fun <T> AgentDatabase.atomic(block:()->T):T=runInTransaction(Callable { block() })

class RoomAgentStore(private val db:AgentDatabase):AgentStore {
 private val dao get()=db.dao()
 override fun createCampaign(campaign:Campaign)=db.atomic {
  validate(campaign)
  val module=dao.module(campaign.module.id,campaign.module.version)?:error("MODULE_NOT_INSTALLED")
  require(module.hash==campaign.module.hash && module.state=="READY") { "MODULE_VERSION_MISMATCH" }
  val definition=RoomModuleCatalog(db).get(campaign.module)
  require(campaign.rules==VersionRef(definition.rules.adapterId,definition.rules.adapterVersion,definition.contentHash)){"RULE_VERSION_MISMATCH"}
  dao.insertCampaign(campaign.row(null)); dao.insertCampaignMeta(CampaignMetaRow(campaign.id,null,false,false,null,null,null,campaign.updatedAt,false)); saveWorld(campaign)
  dao.insertInitial(textParts(encode(campaign.world)).mapIndexed{i,p->InitialStatePart(campaign.id,i,p)})
 }
 override fun loadCampaign(campaignId:String):Campaign?=db.atomic { dao.campaign(campaignId)?.let(::campaign) }
 override fun loadTurn(campaignId:String,turnId:String):Turn?=db.atomic {dao.turn(campaignId,turnId)?.let(::hydrateTurn)}
 override fun findTurnByClientMessage(campaignId:String,clientMessageId:String):Turn?=db.atomic {dao.clientTurn(campaignId,clientMessageId)?.let(::hydrateTurn)}
 override fun listCampaigns(offset:Int,limit:Int):List<CampaignHeader> { require(offset>=0); return dao.campaigns(offset,page(limit)).map { CampaignHeader(CampaignRef(LONGFORM_ENGINE,it.id),it.title,it.updatedAt) } }
 override fun history(campaignId:String,beforeOrdinal:Long?,limit:Int):List<Turn> =db.atomic {dao.turns(campaignId,beforeOrdinal,page(limit)).map(::hydrateTurn)}
 override fun events(campaignId:String,afterSeq:Long,limit:Int):List<WorldEvent> =dao.events(campaignId,afterSeq,page(limit)).map{decode(it.json)}
 override fun <T> transaction(campaignId:String,block:(AgentTransaction)->T):T=db.atomic {
  val original=dao.campaign(campaignId)?:error("CAMPAIGN_NOT_FOUND")
  var current=campaign(original)
  var pending=original.pendingTurnId
  val tx=object:AgentTransaction {
   override val session get()=CampaignSession(current,pending?.let{loadTurn(campaignId,it)})
   override fun saveCampaign(campaign:Campaign) {
    require(campaign.id==campaignId && campaign.module==current.module && campaign.rules==current.rules) { "IMMUTABLE_CAMPAIGN_BINDING" }
    require(campaign.revision>=current.revision && campaign.lastEventSeq>=current.lastEventSeq)
    validate(campaign); val worldChanged=campaign.world!=current.world;current=campaign
    dao.updateCampaign(campaign.row(pending)); if(worldChanged)saveWorld(campaign)
   }
   override fun saveTurn(turn:Turn) {
    require(turn.campaignId==campaignId)
    val terminal=turn.phase in setOf(TurnPhase.COMPLETE,TurnPhase.CANCELLED)
    require(terminal || pending==null || pending==turn.id) { "TURN_ALREADY_PENDING" }
    persistTurn(turn)
    pending=if(terminal && pending==turn.id) null else if(!terminal) turn.id else pending
    dao.updateCampaign(current.row(pending))
    turn.result?.let { result->
     val id="event:$campaignId:${result.eventSeq}"
     val roll=result.dice?.let{"；骰点 ${it.value}/${it.threshold}"}.orEmpty()
     val outcome=if(result.success)"成功" else "未成功"
     val omens=result.notices.joinToString(""){"；征兆：$it"}
     if(dao.memory(campaignId,id)==null) RoomMemoryRepository(db).save(MemoryItem(id,campaignId,"${result.publicSummary}；$outcome$roll$omens",turn.plan?.proposal?.targetIds.orEmpty(),result.eventSeq,result.eventSeq,Visibility(Audience.PUBLIC),"ACTIVE",sourceTurnId=turn.id))
    }
    if(turn.phase==TurnPhase.COMPLETE && turn.result==null && turn.narration!=null) {
     val id="conversation:$campaignId:${turn.id}"
     if(dao.memory(campaignId,id)==null) {
      fun quote(text:String,limit:Int)=text.take(limit).trimEnd{Character.isHighSurrogate(it)}
      val text="玩家原话：${quote(turn.playerText,4000)}；主持回应：${quote(requireNotNull(turn.narration),7000)}（对话记录，不代表世界状态）"
      RoomMemoryRepository(db).save(MemoryItem(id,campaignId,text,emptyList(),current.lastEventSeq,current.lastEventSeq,Visibility(Audience.PUBLIC),"ACTIVE",sourceTurnId=turn.id,kind="CONVERSATION"))
     }
    }
    if(turn.phase==TurnPhase.COMPLETE && turn.ordinal%20L==0L && current.lastEventSeq>0) dao.insertJob(DerivedJobRow("summary:$campaignId:${turn.ordinal}",campaignId,"SUMMARY",maxOf(1,current.lastEventSeq-19),current.lastEventSeq,"PENDING",null))
   }
   override fun appendEvent(event:WorldEvent) {
    require(event.campaignId==campaignId && event.eventVersion==RuleAdapters.eventVersion(current.rules.id) && event.seq==current.lastEventSeq+1)
    val latest=dao.events(campaignId,event.seq-1,1)
    require(latest.isEmpty()) { "DUPLICATE_EVENT" }
    dao.insertEvent(EventRow(campaignId,event.seq,event.turnId,event.eventVersion,encode(event)))
   }
  }
  block(tx)
 }
 internal fun persistTurn(turn:Turn) {
  val previous=dao.turn(turn.campaignId,turn.id)
  val row=TurnRow(turn.id,turn.campaignId,turn.clientMessageId,turn.ordinal,turn.phase.name,encode(turn.copy(attempts=emptyList(),tools=emptyList(),narration=null)))
  if(previous==null)dao.insertTurn(row) else {require(previous.clientMessageId==turn.clientMessageId && previous.ordinal==turn.ordinal);dao.updateTurn(row)}
  dao.insertAttempts(turn.attempts.mapIndexed{i,a->AttemptRow(a.id,turn.id,i,a.purpose,a.fingerprint,a.status,a.usage?.inputTokens,a.usage?.outputTokens)})
  dao.insertTools(turn.tools.mapIndexed{i,t->ToolResultRow(turn.id,t.invocationId,i,encode(t))})
  turn.narration?.let{dao.insertNarration(NarrationRow(turn.id,it))}
 }
 private fun hydrateTurn(row:TurnRow):Turn=decode<Turn>(row.json).copy(attempts=dao.attempts(row.id).map{ModelAttempt(it.id,it.purpose,it.fingerprint,it.status,if(it.inputTokens!=null && it.outputTokens!=null)Usage(it.inputTokens,it.outputTokens) else null)},tools=dao.tools(row.id).map{decode(it.json)},narration=dao.narration(row.id)?.text)
 private fun validate(c:Campaign) {
  require(c.id.isNotBlank() && c.title.isNotBlank() && c.title.length<=100 && c.revision>=0 && c.lastEventSeq>=0)
  require(c.world.facts.map{it.id}.distinct().size==c.world.facts.size)
  require(c.world.npcs.map{it.id}.distinct().size==c.world.npcs.size)
  require(c.world.knowledge.all{k->c.world.facts.any{it.id==k.factId} && (k.knowerId=="player" || c.world.npcs.any{it.id==k.knowerId})}) { "INVALID_KNOWLEDGE_REFERENCE" }
 }
 private fun Campaign.row(pending:String?)=CampaignRow(id,title,module.id,module.version,module.hash,encode(rules),revision,lastEventSeq,world.locationId,encode(world.resources),encode(world.skills),world.gameTime,allowGeneration,modelFingerprint,narratorFingerprint,updatedAt,pending,nextTurnOrdinal,world.d20?.let{encode(it)},contentLevel,sceneHold,world.flags.takeIf{it.isNotEmpty()}?.let{encode(it)},world.schedule.takeIf{it.isNotEmpty()}?.let{encode(it)})
 private fun campaign(r:CampaignRow):Campaign {
  val rows=dao.world(r.id)
  fun <T> entities(kind:String,read:(String)->T)=rows.filter{it.kind==kind}.map{read(it.json)}
  val relations=dao.relations(r.id).groupBy{it.npcId};val goals=dao.goals(r.id).associate{it.npcId to it.goal}
  val npcs=dao.npcs(r.id).map{NpcState(it.id,it.name,it.locationId,it.status,it.personality,goals.getValue(it.id),relations[it.id].orEmpty().associate{r->r.dimension to r.value},it.nextActionAt)}
  val facts=dao.facts(r.id).map{Fact(it.id,it.text,Visibility(Audience.valueOf(it.audience),it.subjectId),it.locked,decode(it.sourceJson))}
  val world=WorldState(r.locationId,decode(r.resourcesJson),decode(r.skillsJson),npcs,entities("ITEM"){decode<Item>(it)},facts,entities("OBJECTIVE"){decode<Objective>(it)},dao.knowledge(r.id).map{KnowledgeEdge(it.knowerId,it.factId,it.learnedAtSeq,it.method,it.belief)},entities("GENERATED"){decode<EntityCreation>(it)},r.gameTime,r.d20Json?.let{decode<D20State>(it)},r.flagsJson?.let{decode<Map<String,String>>(it)}.orEmpty(),r.scheduleJson?.let{decode<Map<String,ScheduleStatus>>(it)}.orEmpty())
  return Campaign(r.id,r.title,VersionRef(r.moduleId,r.moduleVersion,r.moduleHash),decode(r.rulesJson),r.revision,r.lastEventSeq,world,r.allowGeneration,r.modelFingerprint,r.narratorFingerprint,r.updatedAt,r.nextTurnOrdinal,r.contentLevel,r.sceneHold)
 }
 private fun saveWorld(c:Campaign) {
  val rows=mutableListOf<WorldEntityRow>()
  c.world.items.forEach{rows+=WorldEntityRow(c.id,"ITEM",it.id,encode(it))}
  c.world.objectives.forEach{rows+=WorldEntityRow(c.id,"OBJECTIVE",it.id,encode(it))}
  c.world.generated.forEach{rows+=WorldEntityRow(c.id,"GENERATED",it.id,encode(it))}
  dao.clearKnowledge(c.id); dao.clearWorld(c.id); dao.clearNpcs(c.id);dao.clearFacts(c.id);dao.insertWorld(rows)
  dao.insertNpcs(c.world.npcs.map{NpcRow(c.id,it.id,it.name,it.locationId,it.status,it.personality,it.nextActionAt)})
  dao.insertGoals(c.world.npcs.map{NpcGoalRow(c.id,it.id,it.goal)})
  dao.insertRelations(c.world.npcs.flatMap{npc->npc.relationships.map{(key,value)->require(key in setOf("trust","fear","respect") && value in -100..100);NpcRelationRow(c.id,npc.id,key,value)}})
  dao.insertFacts(c.world.facts.map{FactRow(c.id,it.id,it.text,it.visibility.audience.name,it.visibility.subjectId,it.locked,encode(it.source))})
  dao.insertKnowledge(c.world.knowledge.map{KnowledgeRow(c.id,it.knowerId,it.factId,it.learnedAtSeq,it.method,it.belief)})
 }
}
