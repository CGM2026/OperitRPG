package com.ai.assistance.operit.rpg.agent.engine

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.memory.*
import com.ai.assistance.operit.rpg.agent.rules.*
import com.ai.assistance.operit.rpg.agent.tools.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.UUID

class AgentCoordinator(private val store:AgentStore,private val catalog:ModuleCatalog,private val rules:RuleRepository,private val memories:MemoryRepository,private val planner:TextModelPort,private val narrator:TextModelPort,private val dice:DiceSource,private val clock:Clock) {
    private val contexts=ContextAssembler(memories)
    private val tools=ToolRegistry(rules,memories)
    fun create(id:String,title:String,moduleRef:VersionRef,characterId:String,allowGeneration:Boolean,modelFingerprint:String,narratorFingerprint:String):Campaign {
        val m=catalog.get(moduleRef); RuleAdapters.forModule(m).validateModule(m)
        require(m.contentHash==moduleRef.hash && modelFingerprint.isNotBlank() && narratorFingerprint.isNotBlank())
        val c=Campaign(id,title,moduleRef,VersionRef(m.rules.adapterId,m.rules.adapterVersion,m.contentHash),0,0,initialWorld(m,characterId),allowGeneration,modelFingerprint,narratorFingerprint,clock.nowMillis(),contentLevel=m.rules.defaultContentLevel)
        store.createCampaign(c); return c
    }
    suspend fun submit(campaignId:String,clientMessageId:String,text:String):Turn {
        require(text.isNotBlank() && text.length<=8000 && clientMessageId.isNotBlank())
        store.findTurnByClientMessage(campaignId,clientMessageId)?.let { return it }
        val turn=store.transaction(campaignId) { tx ->
            require(tx.session.pendingTurn==null) { "TURN_PENDING" }
            val c=tx.session.campaign
            tx.saveCampaign(c.copy(nextTurnOrdinal=c.nextTurnOrdinal+1))
            Turn(UUID.randomUUID().toString(),campaignId,c.nextTurnOrdinal,clientMessageId,text,c.revision,TurnPhase.RECEIVED).also { tx.saveTurn(it) }
        }
        return run(campaignId,turn.id)
    }
    suspend fun confirm(campaignId:String,turnId:String):Turn {
        mutate(campaignId,turnId) { _,t -> require(t.phase==TurnPhase.AWAITING_CONFIRMATION); t.copy(phase=TurnPhase.READY,error=null) }
        commit(campaignId,turnId); return narrate(campaignId,turnId)
    }
    suspend fun resume(campaignId:String,turnId:String):Turn {
        val t=requireNotNull(store.loadTurn(campaignId,turnId))
        require(t.phase !in setOf(TurnPhase.COMPLETE,TurnPhase.CANCELLED,TurnPhase.AWAITING_CONFIRMATION)) { "TURN_NOT_RESUMABLE" }
        return when(t.phase) {
            TurnPhase.COMMITTED,TurnPhase.NARRATING -> narrate(campaignId,turnId)
            TurnPhase.READY -> { commit(campaignId,turnId); narrate(campaignId,turnId) }
            else -> { mutate(campaignId,turnId) { _,old -> old.copy(phase=TurnPhase.GATHERING,error=null,attempts=old.attempts.map { if(it.status=="RUNNING")it.copy(status="INTERRUPTED") else it }) }; run(campaignId,turnId) }
        }
    }
    fun cancel(campaignId:String,turnId:String):Turn {
        val t=mutate(campaignId,turnId) { _,old ->
            require(old.phase !in setOf(TurnPhase.COMMITTED,TurnPhase.NARRATING,TurnPhase.COMPLETE,TurnPhase.CANCELLED)) { "ALREADY_COMMITTED" }
            old.copy(phase=TurnPhase.CANCELLED,error=null)
        }
        t.attempts.filter { it.status=="RUNNING" }.forEach { portFor(it.purpose).cancel(it.id) }; return t
    }
    fun rebindPlanner(campaignId:String,fingerprint:String):Campaign = store.transaction(campaignId) { tx -> require(tx.session.pendingTurn==null) { "TURN_PENDING" }; require(fingerprint.isNotBlank()); tx.session.campaign.copy(modelFingerprint=fingerprint,updatedAt=clock.nowMillis()).also { tx.saveCampaign(it) } }
    fun rebindNarrator(campaignId:String,fingerprint:String):Campaign = store.transaction(campaignId) { tx -> require(tx.session.pendingTurn==null) { "TURN_PENDING" }; require(fingerprint.isNotBlank()); tx.session.campaign.copy(narratorFingerprint=fingerprint,updatedAt=clock.nowMillis()).also { tx.saveCampaign(it) } }
    /** Table command: the next narration is told to wind the scene down; cleared once that narration completes. */
    fun holdScene(campaignId:String):Campaign = store.transaction(campaignId) { tx ->
        val c=tx.session.campaign; require(catalog.get(c.module).rules.contentLevels.isNotEmpty()) { "NO_CONTENT_LEVELS" }
        c.copy(sceneHold=true,updatedAt=clock.nowMillis()).also { tx.saveCampaign(it) }
    }
    /** Table command: moves the content level by [delta], clamped to the range the module declares. */
    fun adjustContentLevel(campaignId:String,delta:Int):Campaign = store.transaction(campaignId) { tx ->
        val c=tx.session.campaign; val levels=catalog.get(c.module).rules.contentLevels; require(levels.isNotEmpty()) { "NO_CONTENT_LEVELS" }
        c.copy(contentLevel=(c.contentLevel+delta).coerceIn(0,levels.lastIndex),updatedAt=clock.nowMillis()).also { tx.saveCampaign(it) }
    }
    /** Explicit user choice for a committed result whose narration model became unavailable. */
    fun rebindInterruptedNarration(campaignId:String,turnId:String,fingerprint:String):Campaign = store.transaction(campaignId) { tx ->
        val t=requireNotNull(tx.session.pendingTurn)
        require(t.id==turnId && t.result!=null && t.phase in setOf(TurnPhase.COMMITTED,TurnPhase.NARRATING) && t.error!=null && t.attempts.none { it.status=="RUNNING" }) { "NARRATION_NOT_INTERRUPTED" }
        require(fingerprint.isNotBlank())
        tx.session.campaign.copy(narratorFingerprint=fingerprint,updatedAt=clock.nowMillis()).also { tx.saveCampaign(it) }
    }
    /** No network is restarted here; the caller displays explicit continue/cancel controls. */
    fun interrupt(campaignId:String):Turn? = store.transaction(campaignId) { tx -> tx.session.pendingTurn?.let { t -> t.copy(attempts=t.attempts.map { if(it.status=="RUNNING")it.copy(status="INTERRUPTED") else it },error="INTERRUPTED").also { tx.saveTurn(it) } } }
    private suspend fun run(campaignId:String,turnId:String):Turn {
        val started=clock.nowMillis()
        val priorAttempts=requireNotNull(store.loadTurn(campaignId,turnId)).attempts.count { it.purpose=="planner" }
        try {
            mutate(campaignId,turnId) { _,t -> require(t.phase in setOf(TurnPhase.RECEIVED,TurnPhase.GATHERING)); t.copy(phase=TurnPhase.GATHERING,error=null) }
            while(true) {
                require(clock.nowMillis()-started<=180000) { "TURN_TIME_BUDGET" }
                val c=requireNotNull(store.loadCampaign(campaignId)); val m=catalog.get(c.module); val t=requireNotNull(store.loadTurn(campaignId,turnId))
                require(t.attempts.count { it.purpose=="planner" }-priorAttempts<4) { "PLANNING_BUDGET" }
                val remaining=180000-(clock.nowMillis()-started)
                require(remaining>0) { "TURN_TIME_BUDGET" }
                val reply=withTimeout(remaining.coerceAtMost(60000)) { request(c,t,contexts.planning(c,m,t),"planner",TurnPhase.GATHERING) }
                val step=AgentProtocol.parse(reply.text)
                when(step.kind) {
                    "read_tools" -> {
                        require(t.tools.size+requireNotNull(step.calls).size<=8) { "TOOL_BUDGET" }
                        step.calls.forEach { call ->
                            val current=requireNotNull(store.loadTurn(campaignId,turnId))
                            require(current.tools.none { it.invocationId==call.invocationId }) { "DUPLICATE_TOOL_ID" }
                            val result=tools.execute(call,c,m,RoleContext(c.id,Audience.GM))
                            require(current.tools.lastOrNull()?.argumentHash!=result.argumentHash) { "REPEATED_TOOL" }
                            require(current.tools.sumOf { it.output.toByteArray().size }+result.output.toByteArray().size<=32768) { "TOOL_OUTPUT_BUDGET" }
                            mutate(campaignId,turnId) { campaign,old -> require(campaign.revision==c.revision && old.phase==TurnPhase.GATHERING) { "STALE_ATTEMPT" }; old.copy(tools=old.tools+result) }
                        }
                    }
                    "propose_action" -> {
                        val rawProposal=requireNotNull(step.proposal)
                        require(rawProposal.creations.all { it.source.authority=="generated" }) { "INVALID_GENERATED_SOURCE" }
                        val p=rawProposal.copy(creations=rawProposal.creations.map { creation -> creation.copy(source=SourceRef("generated:$turnId",digest(c.id+turnId+creation.id+creation.description),creation.id,"generated")) })
                        validatePublicText(p.publicSummary,c)
                        val current=requireNotNull(store.loadTurn(campaignId,turnId))
                        val evidence=current.tools.flatMap { result ->
                            if(result.sourceRefs.isEmpty())emptyList() else {
                                val element=StrictJson.parse(result.output)
                                val entries=if(element is JsonArray)element.toList() else listOf(element)
                                entries.mapNotNull { entry ->
                                    if(entry is JsonObject && "ruleId" in entry)StrictJson.json.decodeFromJsonElement<RuleEvidence>(entry) else null
                                }.filter { it in m.rules.evidence }
                            }
                        }.distinct().filter { it.ruleId in p.ruleRefs }
                        RuleAdapters.forModule(m).validateProposal(c,m,p,evidence)
                        val plan=freeze(c,p,evidence)
                        val confirmation=p.requiresConfirmation || p.temporaryRuling || p.creations.isNotEmpty()
                        mutate(campaignId,turnId) { campaign,old -> require(campaign.revision==c.revision && old.phase==TurnPhase.GATHERING) { "STALE_ATTEMPT" }; old.copy(plan=plan,phase=if(confirmation)TurnPhase.AWAITING_CONFIRMATION else TurnPhase.READY) }
                        if(confirmation)return requireNotNull(store.loadTurn(campaignId,turnId))
                        commit(campaignId,turnId); return narrate(campaignId,turnId)
                    }
                    else -> {
                        val text=requireNotNull(step.text); validatePublicText(text,c)
                        return mutate(campaignId,turnId) { _,old -> require(old.phase==TurnPhase.GATHERING); old.copy(phase=TurnPhase.COMPLETE,narration=text) }
                    }
                }
            }
        } catch(e:Exception) { return fail(campaignId,turnId,e) }
    }
    private fun commit(campaignId:String,turnId:String):Turn = store.transaction(campaignId) { tx ->
        val c=tx.session.campaign; val t=requireNotNull(tx.session.pendingTurn); require(t.id==turnId)
        if(t.phase==TurnPhase.COMMITTED)return@transaction t
        require(t.phase==TurnPhase.READY)
        val plan=requireNotNull(t.plan); require(freeze(c,plan.proposal,plan.evidence).planHash==plan.planHash) { "STALE_PROPOSAL" }
        val m=catalog.get(c.module); val adapter=RuleAdapters.forModule(m); val (event,result)=adapter.resolve(c,plan,m,turnId,dice)
        val world=adapter.applyRecordedEvent(c.world,event,m)
        tx.appendEvent(event)
        tx.saveCampaign(c.copy(world=world,revision=c.revision+1,lastEventSeq=event.seq,updatedAt=clock.nowMillis()))
        t.copy(phase=TurnPhase.COMMITTED,result=result).also { tx.saveTurn(it) }
    }
    private suspend fun narrate(campaignId:String,turnId:String):Turn {
        try {
            val c=requireNotNull(store.loadCampaign(campaignId)); val m=catalog.get(c.module)
            val t=mutate(campaignId,turnId) { _,old -> require(old.phase in setOf(TurnPhase.COMMITTED,TurnPhase.NARRATING)); old.copy(phase=TurnPhase.NARRATING,error=null) }
            val reply=request(c,t,contexts.narration(c,m,t),"narrator",TurnPhase.NARRATING)
            validatePublicText(reply.text,c)
            return store.transaction(campaignId) { tx ->
                val old=requireNotNull(tx.session.pendingTurn); require(old.id==turnId && old.phase==TurnPhase.NARRATING) { "TURN_NOT_PENDING" }
                // The hold only applies to the narration that was assembled with it, so it is cleared exactly when that narration lands.
                if(c.sceneHold)tx.saveCampaign(tx.session.campaign.copy(sceneHold=false,updatedAt=clock.nowMillis()))
                old.copy(phase=TurnPhase.COMPLETE,narration=reply.text,error=null).also { tx.saveTurn(it) }
            }
        } catch(e:Exception) { return fail(campaignId,turnId,e) }
    }
    private fun portFor(purpose:String):TextModelPort = when(purpose) { "planner" -> planner; "narrator","npc_speaker" -> narrator; else -> throw IllegalArgumentException("UNKNOWN_PURPOSE") }
    private fun fingerprintFor(c:Campaign,purpose:String):String = when(purpose) { "planner" -> c.modelFingerprint; "narrator","npc_speaker" -> c.narratorFingerprint; else -> throw IllegalArgumentException("UNKNOWN_PURPOSE") }
    private suspend fun request(c:Campaign,t:Turn,packet:ContextPacket,purpose:String,phase:TurnPhase):TextModelReply {
        val id=UUID.randomUUID().toString(); val port=portFor(purpose); val fingerprint=fingerprintFor(c,purpose)
        mutate(c.id,t.id) { campaign,old -> require(old.phase==phase && fingerprintFor(campaign,purpose)==fingerprint); old.copy(attempts=old.attempts+ModelAttempt(id,purpose,fingerprint,"RUNNING")) }
        val reply=try { withTimeout(60000) { port.generate(id,packet) } } catch(e:Exception) { port.cancel(id); throw e }
        require(reply.text.toByteArray().size<=65536) { "MODEL_OUTPUT_BUDGET" }
        mutate(c.id,t.id) { campaign,old -> require(old.phase==phase && fingerprintFor(campaign,purpose)==fingerprint && old.attempts.any { it.id==id && it.status=="RUNNING" }) { "STALE_ATTEMPT" }; old.copy(attempts=old.attempts.map { if(it.id==id)it.copy(status="COMPLETE",usage=reply.usage) else it }) }
        return reply
    }
    private fun mutate(campaignId:String,turnId:String,block:(Campaign,Turn)->Turn):Turn = store.transaction(campaignId) { tx -> val t=requireNotNull(tx.session.pendingTurn); require(t.id==turnId) { "TURN_NOT_PENDING" }; block(tx.session.campaign,t).also { tx.saveTurn(it) } }
    private fun fail(campaignId:String,turnId:String,e:Exception):Turn {
        val code=e.message?.takeIf { it.matches(Regex("[A-Z_]{3,80}")) }?:"REQUEST_FAILED"
        val old=requireNotNull(store.loadTurn(campaignId,turnId))
        if(old.phase in setOf(TurnPhase.CANCELLED,TurnPhase.COMPLETE))return old
        return mutate(campaignId,turnId) { _,t -> t.copy(error=code,attempts=t.attempts.map { if(it.status=="RUNNING")it.copy(status="FAILED") else it }) }
    }
    private fun validatePublicText(text:String,c:Campaign) {
        require(text.isNotBlank() && text.length<=12000) { "INVALID_NARRATION" }
        c.world.facts.filter { it.visibility.audience!=Audience.PUBLIC }.forEach { fact -> require(!text.contains(fact.id) && !text.contains(fact.text)) { "PRIVATE_CONTENT_IN_OUTPUT" } }
    }
}
