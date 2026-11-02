package com.ai.assistance.operit.rpg.agent.contract

/** All session writes, including event/result/world projection, commit or roll back together. */
interface AgentTransaction {
    val session: CampaignSession
    fun saveCampaign(campaign: Campaign)
    fun saveTurn(turn: Turn)
    fun appendEvent(event: WorldEvent)
}
interface AgentStore {
    fun createCampaign(campaign: Campaign)
    fun loadCampaign(campaignId: String): Campaign?
    fun loadTurn(campaignId: String, turnId: String): Turn?
    fun findTurnByClientMessage(campaignId: String, clientMessageId: String): Turn?
    fun <T> transaction(campaignId: String, block: (AgentTransaction) -> T): T
    fun listCampaigns(offset: Int, limit: Int): List<CampaignHeader>
    fun history(campaignId: String, beforeOrdinal: Long?, limit: Int): List<Turn>
    fun events(campaignId: String, afterSeq: Long, limit: Int): List<WorldEvent>
}
interface ModuleCatalog { fun get(ref: VersionRef): ValidatedModule; fun list(): List<ValidatedModule>; fun install(module: ValidatedModule) }
interface RuleRepository { fun search(role: RoleContext, module: VersionRef, query: String, limit: Int = 5): List<RuleEvidence>; fun read(role: RoleContext, module: VersionRef, sourceId: String, anchor: String): RuleEvidence? }
interface MemoryRepository { fun search(role: RoleContext, query: String, entityIds: List<String>, limit: Int = 5): List<MemoryItem>; fun recent(role: RoleContext, limit: Int): List<MemoryItem>; fun save(item: MemoryItem) }
interface TextModelPort { suspend fun generate(attemptId: String, packet: ContextPacket): TextModelReply; fun cancel(attemptId: String) }
fun interface DiceSource { fun d100(): Int }
fun interface Clock { fun nowMillis(): Long }
