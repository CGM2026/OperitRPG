package com.ai.assistance.operit.rpg.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.util.UUID

interface GameStore {
    suspend fun <T> transaction(campaignId: String, block: (CampaignRecord) -> T): T
    suspend fun create(record: CampaignRecord)
    suspend fun list(): List<CampaignRecord>
}
interface ModelGateway {
    suspend fun plan(request: ModelRequest): ModelReply
    suspend fun narrate(request: ModelRequest): ModelReply
    suspend fun cancel(attemptId: String)
}
fun interface Clock { fun now(): Long }
object SystemClock : Clock { override fun now() = System.currentTimeMillis() }
fun interface IdGenerator { fun next(): String }
object UuidGenerator : IdGenerator { override fun next() = UUID.randomUUID().toString() }
class InMemoryGameStore : GameStore {
    private val mutex = Mutex(); private val records = linkedMapOf<String, String>()
    override suspend fun create(record: CampaignRecord) = mutex.withLock { require(record.id !in records); records[record.id] = RpgJson.instance.encodeToString(record) }
    override suspend fun list(): List<CampaignRecord> = mutex.withLock { records.values.map { RpgJson.instance.decodeFromString<CampaignRecord>(it) } }
    override suspend fun <T> transaction(campaignId: String, block: (CampaignRecord) -> T): T = mutex.withLock {
        val record = RpgJson.instance.decodeFromString<CampaignRecord>(records[campaignId] ?: throw NoSuchElementException(campaignId))
        val result = block(record)
        records[campaignId] = RpgJson.instance.encodeToString(record)
        result
    }
}
/** Explicit debug/test gateway; production wiring must select it deliberately. */
class FakeModelGateway : ModelGateway {
    override suspend fun plan(request: ModelRequest): ModelReply {
        val input = RpgJson.instance.parseToJsonElement(request.userPrompt).jsonObject.getValue("input").jsonPrimitive.content
        val allowed = RpgJson.instance.parseToJsonElement(request.userPrompt).jsonObject["allowedActions"]!!.jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        val phrases = mapOf("move.yard_office" to listOf("去办公室", "前往办公室"), "move.office_yard" to listOf("回院门", "返回院门"), "move.yard_dock" to listOf("去码头", "前往码头"), "move.dock_yard" to listOf("回院门", "返回院门"), "move.office_archive" to listOf("进档案室", "去档案室", "进入档案室"), "move.archive_office" to listOf("回办公室", "返回办公室"), "inspect.office_note" to listOf("便条"), "inspect.archive_ledger" to listOf("账簿", "查账", "货号登记"), "talk.keeper_delivery" to listOf("问门房", "阿远去哪"), "talk.clerk_access" to listOf("说服文员"), "climb.archive_window" to listOf("爬窗", "攀爬"), "talk.boatman_tide" to listOf("问船工", "潮水"), "inspect.dock_marks" to listOf("痕迹"), "rescue.boatman_help" to listOf("救阿远", "救人", "请船工帮忙"), "use.bandage" to listOf("绷带", "治疗"), "wait" to listOf("等待", "等一会"))
        val id = allowed.firstOrNull { it == input } ?: allowed.firstOrNull { action -> phrases[action].orEmpty().any { input.contains(it) } }
        val text = when {
            id != null -> "{\"schema_version\":1,\"kind\":\"action\",\"action_id\":\"$id\",\"requires_confirmation\":${input.contains("然后") || input.contains("再去")},\"public_intent\":\"${RainyDock.actions.single { it.id == id }.description}\"}"
            input.contains("救人") || input.contains("救阿远") -> "{\"schema_version\":1,\"kind\":\"clarify\",\"question\":\"需要先核实阿远所在的货棚。你想先调查哪里？\"}"
            else -> "{\"schema_version\":1,\"kind\":\"ooc\"}"
        }
        return ModelReply(text)
    }
    override suspend fun narrate(request: ModelRequest): ModelReply {
        val result = RpgJson.instance.parseToJsonElement(request.userPrompt).jsonObject.getValue("mechanicalResult").jsonObject
        return ModelReply(result.getValue("publicSummary").jsonPrimitive.content)
    }
    override suspend fun cancel(attemptId: String) = Unit
}
