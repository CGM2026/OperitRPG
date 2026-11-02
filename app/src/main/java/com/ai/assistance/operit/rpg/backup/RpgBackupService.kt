package com.ai.assistance.operit.rpg.backup

import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable private data class FileDigest(val bytes: Long, val sha256: String)
@Serializable private data class Manifest(val formatVersion: Int, val scenarioId: String, val scenarioVersion: String,
    val scenarioHash: String, val rulesVersion: String, val exportedAt: Long, val files: Map<String, FileDigest>)
@Serializable private data class Narration(val turnId: String, val text: String)

/** Complete GM archive, including scenario state. No provider configuration or request attempts. */
class RpgBackupService(private val store: GameStore) {
    companion object {
        val UNBOUND = ModelConfiguration("UNBOUND", "UNBOUND", "UNBOUND", "UNBOUND")
        private const val COMPRESSED_LIMIT = 10 * 1024 * 1024
        private const val EXPANDED_LIMIT = 20 * 1024 * 1024
        private val DATA_FILES = setOf("campaign.json", "turns.jsonl", "events.jsonl", "narrations.jsonl")
        private val ALL_FILES = DATA_FILES + "manifest.json"
    }
    private val json = RpgJson.instance

    suspend fun export(campaignId: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val record = store.transaction(campaignId) { source ->
            // Serialize inside the transaction so no mutable object escapes the snapshot boundary.
            json.decodeFromString<CampaignRecord>(json.encodeToString(source))
        }
        validate(record, requireSanitized = false)
        val clean = sanitize(record)
        val data = linkedMapOf(
            "campaign.json" to json.encodeToString(clean.copy(turns = mutableListOf(), events = mutableListOf())).toByteArray(Charsets.UTF_8),
            "turns.jsonl" to lines(clean.turns.map { json.encodeToString(it.copy(narration = null)) }),
            "events.jsonl" to lines(clean.events.map { json.encodeToString(it) }),
            "narrations.jsonl" to lines(clean.turns.mapNotNull { turn -> turn.narration?.let { json.encodeToString(Narration(turn.id, it)) } })
        )
        val manifest = Manifest(1, clean.scenarioId, clean.scenarioVersion, clean.scenarioHash, clean.rulesVersion,
            System.currentTimeMillis(), data.mapValues { FileDigest(it.value.size.toLong(), digest(it.value)) })
        data["manifest.json"] = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(data.values.sumOf { it.size.toLong() } <= EXPANDED_LIMIT) { "存档展开后超过 20 MiB" }
        val packed = ByteArrayOutputStream()
        ZipOutputStream(packed).use { zip -> data.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        require(packed.size() <= COMPRESSED_LIMIT) { "存档超过 10 MiB" }
        output.write(packed.toByteArray()); output.flush()
    }

    suspend fun preview(input: InputStream): CampaignRecord = withContext(Dispatchers.IO) {
        val bytes = boundedRead(input, COMPRESSED_LIMIT)
        val file = File.createTempFile("rpg-import-", ".zip")
        try {
            file.outputStream().use { it.write(bytes) }
            val data = linkedMapOf<String, ByteArray>()
            ZipFile(file).use { zip ->
                val entries = zip.entries(); var expanded = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(data.size < 10 && !entry.isDirectory && entry.name in ALL_FILES && entry.name !in data) { "存档含未知、重复或非法路径" }
                    require(entry.size in 0..EXPANDED_LIMIT.toLong()) { "存档条目大小非法" }
                    val content = zip.getInputStream(entry).use { boundedRead(it, EXPANDED_LIMIT - expanded) }
                    expanded += content.size
                    require(content.size.toLong() == entry.size && CRC32().apply { update(content) }.value == entry.crc) { "存档 ZIP 校验失败" }
                    data[entry.name] = content
                }
            }
            require(data.keys == ALL_FILES) { "存档必须包含五个固定文件" }
            val manifest = json.decodeFromString<Manifest>(utf8(data.getValue("manifest.json")))
            require(manifest.formatVersion == 1 && manifest.files.keys == DATA_FILES && manifest.exportedAt >= 0) { "不支持的存档格式" }
            checkScenario(manifest.scenarioId, manifest.scenarioVersion, manifest.scenarioHash, manifest.rulesVersion)
            manifest.files.forEach { (name, expected) ->
                val content = data.getValue(name)
                require(expected.bytes == content.size.toLong() && expected.sha256 == digest(content)) { "存档文件大小或 SHA-256 不一致：$name" }
            }
            val header = json.decodeFromString<CampaignRecord>(utf8(data.getValue("campaign.json")))
            require(header.turns.isEmpty() && header.events.isEmpty()) { "战役头包含重复记录" }
            val turns = parseLines<Turn>(data.getValue("turns.jsonl"))
            require(turns.all { it.narration == null }) { "旁白必须独立保存" }
            val narrations = parseLines<Narration>(data.getValue("narrations.jsonl"))
            require(narrations.map { it.turnId }.distinct().size == narrations.size) { "重复旁白" }
            val byId = turns.associateBy { it.id }
            narrations.forEach { require(it.turnId in byId) { "旁白引用未知回合" }; byId.getValue(it.turnId).narration = it.text }
            val record = header.copy(turns = turns.toMutableList(), events = parseLines<GameEvent>(data.getValue("events.jsonl")).toMutableList())
            require(record.scenarioId == manifest.scenarioId && record.scenarioVersion == manifest.scenarioVersion && record.scenarioHash == manifest.scenarioHash && record.rulesVersion == manifest.rulesVersion) { "模组清单不一致" }
            validate(record, requireSanitized = true)
            record
        } finally { file.delete() }
    }

    suspend fun importAsNew(record: CampaignRecord): String = withContext(Dispatchers.IO) {
        // Revalidate because callers may hold or modify a preview before confirming it.
        val source = json.decodeFromString<CampaignRecord>(json.encodeToString(record))
        validate(source, requireSanitized = true)
        val campaignId = UUID.randomUUID().toString()
        val turns = source.turns.associate { it.id to UUID.randomUUID().toString() }
        fun clue(c: Clue) = c.copy(sourceTurnId = turns.getValue(c.sourceTurnId))
        val imported = source.copy(id = campaignId, model = UNBOUND,
            state = source.state.copy(discoveredClues = source.state.discoveredClues.map(::clue)),
            turns = source.turns.map { t -> t.copy(id = turns.getValue(t.id), campaignId = campaignId,
                clientMessageId = UUID.randomUUID().toString(), model = UNBOUND, attemptId = null, attempts = mutableListOf(),
                result = t.result?.let { it.copy(addedClues = it.addedClues.map(::clue)) }) }.toMutableList(),
            events = source.events.map { it.copy(id = UUID.randomUUID().toString(), campaignId = campaignId, turnId = turns.getValue(it.turnId)) }.toMutableList())
        validate(imported, requireSanitized = true)
        store.create(imported)
        campaignId
    }

    private fun sanitize(r: CampaignRecord) = r.copy(model = UNBOUND, turns = r.turns.map {
        it.copy(model = UNBOUND, attemptId = null, attempts = mutableListOf())
    }.toMutableList())

    private fun validate(r: CampaignRecord, requireSanitized: Boolean) {
        checkScenario(r.scenarioId, r.scenarioVersion, r.scenarioHash, r.rulesVersion)
        require(r.id.isNotBlank() && r.title.isNotBlank() && r.title.length <= 100 && r.state.playerName.isNotBlank() && r.state.playerName.length <= 40 && r.createdAt >= 0 && r.updatedAt >= r.createdAt) { "战役元数据非法" }
        RainyDock.validate(r.state)
        val turnIds = r.turns.map { it.id }.toSet()
        require(turnIds.size == r.turns.size && r.turns.map { it.clientMessageId }.distinct().size == r.turns.size && r.events.map { it.id }.distinct().size == r.events.size && r.events.all { it.id.isNotBlank() }) { "重复或空记录 ID" }
        require(r.turns.all { it.stage == TurnStage.COMPLETE || it.stage == TurnStage.CANCELLED }) { "请先完成或取消中断回合" }
        if (requireSanitized) require(r.model == UNBOUND && r.turns.all { it.model == UNBOUND && it.attemptId == null && it.attempts.isEmpty() }) { "存档不能包含模型配置或请求记录" }
        var state = RainyDock.initialState(r.state.playerName)
        val eventsByTurn = r.events.groupBy { it.turnId }
        require(r.events.all { it.campaignId == r.id && it.turnId in turnIds && it.visibility == "PUBLIC" }) { "事件外键或可见性非法" }
        var eventIndex = 0
        r.turns.forEachIndexed { index, turn ->
            require(turn.id.isNotBlank() && turn.campaignId == r.id && turn.ordinal == index + 1 && turn.clientMessageId.isNotBlank() && turn.inputText.isNotBlank() && turn.inputText.length <= 2000 && turn.baseRevision == state.revision && turn.createdAt >= r.createdAt) { "回合顺序、内容或外键非法" }
            val plan = turn.plan
            plan?.let {
                val intent = it.publicIntent
                val question = it.question
                require(intent == null || (intent.isNotBlank() && intent.length <= 200)) { "行动意图非法" }
                when (it.kind) {
                    PlanKind.ACTION -> require(it.actionId != null && it.publicIntent != null && it.npcId == null && it.question == null) { "行动计划非法" }
                    PlanKind.DIALOGUE -> require(it.actionId == null && it.npcId == RainyDock.npcAt(state.locationId) && state.status == CampaignStatus.ACTIVE && !it.requiresConfirmation && it.question == null) { "对白计划非法" }
                    PlanKind.OOC -> require(it.actionId == null && it.npcId == null && it.question == null && !it.requiresConfirmation) { "规则问答计划非法" }
                    PlanKind.CLARIFY -> require(it.actionId == null && it.npcId == null && !question.isNullOrBlank() && question.length <= 300 && !it.requiresConfirmation) { "澄清计划非法" }
                }
            }
            require(turn.planHash == plan?.let { sha256(json.encodeToString(it)) } && turn.actionHash == plan?.actionId?.let { RainyDock.actionHash(it) }) { "回合计划校验失败" }
            val events = eventsByTurn[turn.id].orEmpty()
            if (turn.stage == TurnStage.CANCELLED) {
                require(events.isEmpty() && turn.result == null) { "取消回合含结算" }
            } else {
                require(plan != null && !turn.narration.isNullOrBlank()) { "完成回合缺少计划或旁白" }
                if (plan.kind == PlanKind.ACTION) {
                    require(events.size == 1) { "行动必须对应唯一事件" }
                    val event = events.single()
                    require(r.events[eventIndex] == event && event.sequence == eventIndex + 1L && event.schemaVersion == 1 && event.type == "ACTION_RESOLVED" && event.actionId == plan.actionId) { "事件顺序或类型非法" }
                    val action = RainyDock.actions.single { it.id == event.actionId }
                    require(if (action.skill == null) event.roll == null else event.roll in 1..100) { "骰点非法" }
                    val resolved = RainyDock.resolve(state, event.actionId, turn.id, object : DiceSource { override fun nextD100() = requireNotNull(event.roll) })
                    require(turn.result == resolved.second) { "检定结果与事件重放不一致" }
                    state = resolved.first; eventIndex++
                } else {
                    require(events.isEmpty()) { "非行动回合含状态事件" }
                    when (plan.kind) {
                        PlanKind.CLARIFY -> require(turn.result == null && turn.narration == plan.question) { "澄清回合非法" }
                        PlanKind.DIALOGUE, PlanKind.OOC -> require(turn.result == TurnResult(publicSummary = if (plan.kind == PlanKind.DIALOGUE) "与在场人物交谈，世界状态不变。" else "场外规则或回顾，世界状态不变。", status = state.status)) { "非行动结算非法" }
                        else -> error("非法计划")
                    }
                }
            }
        }
        require(eventIndex == r.events.size && state == r.state && RainyDock.replay(RainyDock.initialState(r.state.playerName), r.events) == r.state) { "战役状态与事件重放不一致" }
    }

    private fun checkScenario(id: String, version: String, hash: String, rules: String) {
        require(id == "rainy-dock-v1" && version == "1.0.0" && hash == RainyDock.hash && rules == "prototype-d100-v1") { "不支持的模组版本或规则" }
    }
    private fun lines(values: List<String>) = (if (values.isEmpty()) "" else values.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8)
    private inline fun <reified T> parseLines(bytes: ByteArray): List<T> {
        val text = utf8(bytes)
        if (text.isEmpty()) return emptyList()
        val rows = text.removeSuffix("\n").split('\n')
        require(rows.none { it.isBlank() }) { "JSONL 含空白记录" }
        return rows.map { json.decodeFromString<T>(it) }
    }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun boundedRead(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n == -1) break; require(out.size().toLong() + n <= limit) { "存档大小超过限制" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
