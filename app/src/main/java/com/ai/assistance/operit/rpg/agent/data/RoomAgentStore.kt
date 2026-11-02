package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.contract.*
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
  dao.insertCampaign(campaign.row(null)); saveWorld(campaign)
  dao.insertInitial(textParts(encode(campaign.world)).mapIndexed{i,p->InitialStatePart(campaign.id,i,p)})
 }
 override fun loadCampaign(campaignId:String):Campaign?=db.atomic { dao.campaign(campaignId)?.let(::campaign) }
 override fun loadTurn(campaignId:String,turnId:String):Turn?=dao.turn(campaignId,turnId)?.let{decode(it.json)}
 override fun findTurnByClientMessage(campaignId:String,clientMessageId:String):Turn?=dao.clientTurn(campaignId,clientMessageId)?.let{decode(it.json)}
 override fun listCampaigns(offset:Int,limit:Int):List<CampaignHeader> { require(offset>=0); return dao.campaigns(offset,page(limit)).map { CampaignHeader(CampaignRef(LONGFORM_ENGINE,it.id),it.title,it.updatedAt) } }
 override fun history(campaignId:String,beforeOrdinal:Long?,limit:Int):List<Turn> =dao.turns(campaignId,beforeOrdinal,page(limit)).map{decode(it.json)}
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
    validate(campaign); current=campaign
    dao.updateCampaign(campaign.row(pending)); saveWorld(campaign)
   }
   override fun saveTurn(turn:Turn) {
    require(turn.campaignId==campaignId)
    val terminal=turn.phase in setOf(TurnPhase.COMPLETE,TurnPhase.CANCELLED)
    require(terminal || pending==null || pending==turn.id) { "TURN_ALREADY_PENDING" }
    val previous=dao.turn(campaignId,turn.id)
    if(previous==null) dao.insertTurn(TurnRow(turn.id,campaignId,turn.clientMessageId,turn.ordinal,turn.phase.name,encode(turn)))
    else { require(previous.clientMessageId==turn.clientMessageId && previous.ordinal==turn.ordinal); dao.updateTurn(previous.copy(phase=turn.phase.name,json=encode(turn))) }
    pending=if(terminal && pending==turn.id) null else if(!terminal) turn.id else pending
    dao.updateCampaign(current.row(pending))
    turn.result?.let { result->
     val id="event:$campaignId:${result.eventSeq}"
     val roll=result.dice?.let{"；骰点 ${it.value}/${it.threshold}"}.orEmpty()
     val outcome=if(result.success)"成功" else "未成功"
     if(dao.memory(campaignId,id)==null) RoomMemoryRepository(db).save(MemoryItem(id,campaignId,"${result.publicSummary}；$outcome$roll",turn.plan?.proposal?.targetIds.orEmpty(),result.eventSeq,result.eventSeq,Visibility(Audience.PUBLIC),"ACTIVE"))
    }
    if(turn.phase==TurnPhase.COMPLETE && turn.ordinal%20L==0L && current.lastEventSeq>0) dao.insertJob(DerivedJobRow("summary:$campaignId:${turn.ordinal}",campaignId,"SUMMARY",maxOf(1,current.lastEventSeq-19),current.lastEventSeq,"PENDING",null))
   }
   override fun appendEvent(event:WorldEvent) {
    require(event.campaignId==campaignId && event.eventVersion==1)
    val latest=dao.events(campaignId,event.seq-1,1)
    require(latest.isEmpty()) { "DUPLICATE_EVENT" }
    dao.insertEvent(EventRow(campaignId,event.seq,event.turnId,event.eventVersion,encode(event)))
   }
  }
  block(tx)
 }
 private fun validate(c:Campaign) {
  require(c.id.isNotBlank() && c.title.isNotBlank() && c.title.length<=100 && c.revision>=0 && c.lastEventSeq>=0)
  require(c.world.facts.map{it.id}.distinct().size==c.world.facts.size)
  require(c.world.npcs.map{it.id}.distinct().size==c.world.npcs.size)
  require(c.world.knowledge.all{k->c.world.facts.any{it.id==k.factId} && (k.knowerId=="player" || c.world.npcs.any{it.id==k.knowerId})}) { "INVALID_KNOWLEDGE_REFERENCE" }
 }
 private fun Campaign.row(pending:String?)=CampaignRow(id,title,module.id,module.version,module.hash,encode(rules),revision,lastEventSeq,world.locationId,encode(world.resources),encode(world.skills),world.gameTime,allowGeneration,modelFingerprint,updatedAt,pending,nextTurnOrdinal)
 private fun campaign(r:CampaignRow):Campaign {
  val rows=dao.world(r.id)
  fun <T> entities(kind:String,read:(String)->T)=rows.filter{it.kind==kind}.map{read(it.json)}
  val relations=dao.relations(r.id).groupBy{it.npcId};val goals=dao.goals(r.id).associate{it.npcId to it.goal}
  val npcs=dao.npcs(r.id).map{NpcState(it.id,it.name,it.locationId,it.status,it.personality,goals.getValue(it.id),relations[it.id].orEmpty().associate{r->r.dimension to r.value},it.nextActionAt)}
  val facts=dao.facts(r.id).map{Fact(it.id,it.text,Visibility(Audience.valueOf(it.audience),it.subjectId),it.locked,decode(it.sourceJson))}
  val world=WorldState(r.locationId,decode(r.resourcesJson),decode(r.skillsJson),npcs,entities("ITEM"){decode<Item>(it)},facts,entities("OBJECTIVE"){decode<Objective>(it)},dao.knowledge(r.id).map{KnowledgeEdge(it.knowerId,it.factId,it.learnedAtSeq,it.method,it.belief)},entities("GENERATED"){decode<EntityCreation>(it)},r.gameTime)
  return Campaign(r.id,r.title,VersionRef(r.moduleId,r.moduleVersion,r.moduleHash),decode(r.rulesJson),r.revision,r.lastEventSeq,world,r.allowGeneration,r.modelFingerprint,r.updatedAt,r.nextTurnOrdinal)
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
