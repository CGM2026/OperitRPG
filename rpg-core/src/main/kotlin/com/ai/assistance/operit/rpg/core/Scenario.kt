package com.ai.assistance.operit.rpg.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom

interface DiceSource { fun nextD100(): Int }
class SecureRandomDice : DiceSource { private val random = SecureRandom(); override fun nextD100() = random.nextInt(100) + 1 }
class SequenceDice(vararg values: Int) : DiceSource {
    private val rolls = values.toList(); var consumed: Int = 0; private set
    override fun nextD100(): Int = rolls[consumed++].also { require(it in 1..100) }
}
object PrototypeD100Rules {
    fun check(skill: String, value: Int, roll: Int, hard: Boolean = false): DiceResult {
        require(roll in 1..100); require(value in 0..100)
        val threshold = if (hard) value / 2 else value
        return DiceResult(skill, value, threshold, roll, roll <= threshold, if (hard) "困难" else "普通")
    }
}
@Serializable data class ActionDefinition(val id: String, val description: String, val location: String? = null, val once: Boolean = false, val skill: String? = null, val skillValue: Int = 0, val hard: Boolean = false)
object RainyDock {
    val locations = listOf(PublicLocation("yard", "院门", "门房、雨棚、通往办公室与码头的路"), PublicLocation("office", "办公室", "桌上有便条，内侧通往档案室"), PublicLocation("archive", "档案室", "潮湿账簿、窗台"), PublicLocation("dock", "码头", "绳索、货棚排列，潮水上涨")).associateBy { it.id }
    val connections = mapOf("yard" to listOf("office", "dock"), "office" to listOf("yard", "archive"), "archive" to listOf("office"), "dock" to listOf("yard"))
    val actions = listOf(
        ActionDefinition("move.yard_office", "前往办公室", "yard"), ActionDefinition("move.office_yard", "返回院门", "office"),
        ActionDefinition("move.yard_dock", "前往码头", "yard"), ActionDefinition("move.dock_yard", "返回院门", "dock"),
        ActionDefinition("move.office_archive", "进入档案室", "office"), ActionDefinition("move.archive_office", "返回办公室", "archive"),
        ActionDefinition("talk.keeper_delivery", "询问门房阿远的去向", "yard", true),
        ActionDefinition("talk.clerk_access", "说服文员允许查阅档案", "office", true, "说服", 50),
        ActionDefinition("inspect.office_note", "查看桌上便条", "office", true),
        ActionDefinition("climb.archive_window", "从院门攀爬档案室窗户", "yard", true, "攀爬", 55),
        ActionDefinition("inspect.archive_ledger", "查阅货号账簿", "archive", true, "观察", 60),
        ActionDefinition("talk.boatman_tide", "向船工询问潮水和开船时间", "dock", true),
        ActionDefinition("inspect.dock_marks", "观察码头拖拽痕迹", "dock", true, "观察", 60, true),
        ActionDefinition("rescue.boatman_help", "根据已核实的货号请船工协助寻找阿远", "dock", true),
        ActionDefinition("use.bandage", "使用绷带治疗"), ActionDefinition("wait", "等待一个时间步")
    )
    // Hash comes from the actual packaged bytes, shared with export/import validation.
    val json: String by lazy { javaClass.getResourceAsStream("/rainy-dock-v1.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() } }
    val definition: ScenarioDefinition by lazy { ScenarioDefinition.load(json) }
    val hash: String by lazy { definition; sha256(json) }
    fun initialState(playerName: String = "林舟") = CampaignState(playerName = playerName)
    fun allowedActions(s: CampaignState): List<PublicAction> = actions.filter { available(s, it) }.map { PublicAction(it.id, it.description) }
    private fun available(s: CampaignState, a: ActionDefinition): Boolean {
        if (s.status != CampaignStatus.ACTIVE || s.hp <= 0 || (a.location != null && a.location != s.locationId) || (a.once && a.id in s.resolvedActionIds)) return false
        return when (a.id) {
            "move.office_archive" -> s.accessArchive
            "talk.clerk_access", "climb.archive_window" -> !s.accessArchive
            "use.bandage" -> s.hp in 1..5 && s.inventory.getValue("bandage") > 0
            "rescue.boatman_help" -> s.discoveredClues.map { it.id }.containsAll(listOf("c1", "c2"))
            else -> true
        }
    }
    fun validate(s: CampaignState) {
        require(s.hp in 0..6 && s.maxHp == 6 && s.tick >= 0 && s.alarm >= 0 && s.revision >= 0)
        require(s.locationId in locations && s.visitedLocations.all { it in locations })
        require(s.inventory.keys == setOf("bandage") && s.inventory.values.all { it >= 0 })
        require(s.discoveredClues.all { it.id in setOf("c1", "c2", "c3") } && s.discoveredClues.map { it.id }.distinct().size == s.discoveredClues.size)
        require(s.resolvedActionIds.all { id -> actions.any { it.id == id && it.once } })
    }
    fun actionHash(id: String): String = sha256(hash + ":prototype-d100-v1:" + RpgJson.instance.encodeToString(actions.single { it.id == id }))
    fun resolve(s: CampaignState, actionId: String, turnId: String, dice: DiceSource): Pair<CampaignState, TurnResult> {
        validate(s)
        val a = actions.single { it.id == actionId }; require(available(s, a)) { "ACTION_UNAVAILABLE" }
        val check = a.skill?.let { PrototypeD100Rules.check(it, a.skillValue, dice.nextD100(), a.hard) }
        var next = s; var cost = 1; var summary = a.description
        val clues = mutableListOf<Clue>()
        when {
            actionId.startsWith("move.") -> next = next.copy(locationId = actionId.substringAfter('_'))
            actionId == "talk.keeper_delivery" -> { next = next.copy(knownFacts = next.knownFacts + ("keeper_saw_office" to "阿远傍晚去了办公室")); summary = "门房说：阿远傍晚去了办公室。" }
            actionId == "talk.clerk_access" -> if (check!!.success) { next = next.copy(accessArchive = true); summary = "文员允许你查阅档案。" } else { next = next.copy(alarm = next.alarm + 1, knownFacts = next.knownFacts + ("verify_identity" to "找到阿远留下的便条即可核实调查身份")); summary = "说服失败，警戒增加；找到阿远留下的便条即可核实调查身份。" }
            actionId == "inspect.office_note" -> { clues += Clue("c1", "阿远的便条", "我去处理货号 K17，稍后回来。", turnId); next = next.copy(accessArchive = true); summary = "找到阿远的便条：我去处理货号 K17，稍后回来。便条核实了调查身份，档案室开放。" }
            actionId == "climb.archive_window" -> if (check!!.success) { next = next.copy(accessArchive = true, locationId = "archive"); summary = "攀爬成功，进入档案室。" } else { next = next.copy(hp = next.hp - 1, alarm = next.alarm + 1); summary = "攀爬失败，HP减少1，警戒增加，仍在院门。" }
            actionId == "inspect.archive_ledger" -> { val quality = if (check!!.success) "clear" else "smudged"; clues += Clue("c2", "货号登记", "K17 对应第三号货棚", turnId, quality); if (!check.success) cost++; summary = if (check.success) "账簿清楚记载：K17 对应第三号货棚。" else "账簿湿损，辨认多花一个时间步，但仍读出 K17 对应第三号货棚。" }
            actionId == "talk.boatman_tide" -> { next = next.copy(knownFacts = next.knownFacts + ("boatman_tide" to "必须在船离开前检查货棚，最好先核实货号")); summary = "船工说：必须在船离开前检查货棚，最好先核实货号。" }
            actionId == "inspect.dock_marks" -> if (check!!.success) { clues += Clue("c3", "拖拽痕迹", "新鲜痕迹通向码头货棚一带，说明货物刚刚移动过", turnId); summary = "发现新鲜拖拽痕迹，通向码头货棚一带。" } else { next = next.copy(alarm = next.alarm + 1); summary = "没能辨认拖拽痕迹，警戒增加。" }
            actionId == "rescue.boatman_help" -> { next = next.copy(rescued = true); summary = "你告知船工第三号货棚的位置，与船工移开木箱，找到仍活着的阿远。" }
            actionId == "use.bandage" -> { next = next.copy(hp = minOf(6, next.hp + 2), inventory = next.inventory + ("bandage" to next.inventory.getValue("bandage") - 1)); summary = "消耗一份绷带，HP恢复至${next.hp}。" }
        }
        next = next.copy(tick = next.tick + cost, discoveredClues = next.discoveredClues + clues, visitedLocations = next.visitedLocations + next.locationId, resolvedActionIds = if (a.once) next.resolvedActionIds + actionId else next.resolvedActionIds, revision = next.revision + 1)
        if (s.tick < 8 && next.tick >= 8) next = next.copy(knownFacts = next.knownFacts + ("tide_warning" to "潮水逼近码头下层"))
        next = next.copy(status = when { next.hp <= 0 -> CampaignStatus.FAILED_INCAPACITATED; next.rescued -> CampaignStatus.SUCCESS; next.tick >= 12 -> CampaignStatus.FAILED_DEPARTED; else -> CampaignStatus.ACTIVE })
        validate(next)
        return next to TurnResult(actionId, check, summary, cost, next.hp - s.hp, clues, next.status)
    }
    fun replay(initial: CampaignState, events: List<GameEvent>): CampaignState {
        var state = initial
        events.forEachIndexed { index, event -> require(event.sequence == index + 1L && event.schemaVersion == 1 && event.type == "ACTION_RESOLVED"); state = resolve(state, event.actionId, event.turnId, object : DiceSource { override fun nextD100() = requireNotNull(event.roll) }).first }
        return state
    }
    fun publicView(r: CampaignRecord): PublicCampaignView {
        val s = r.state
        return PublicCampaignView(r.id, r.title, s.playerName, s.hp, s.maxHp, s.tick, locations.getValue(s.locationId), connections.getValue(s.locationId).map { locations.getValue(it).copy(accessible = it != "archive" || s.accessArchive) }, s.inventory, s.discoveredClues, s.knownFacts, s.status, s.revision, r.turns.map { PublicTurn(it.id, it.ordinal, it.inputText, it.stage, it.plan?.publicIntent, it.plan?.question, it.planHash, it.result, it.narration, it.errorCode) }, allowedActions(s), r.model)
    }
    fun npc(s: CampaignState, id: String): NpcKnowledgeView = when (id) {
        "keeper" -> NpcKnowledgeView(id, "门房老周，短句、谨慎", listOf("阿远傍晚去过办公室", "船有出发时限"))
        "clerk" -> NpcKnowledgeView(id, "文员小岚，忙乱但愿意核实", listOf("桌上便条是阿远留下的", "档案室有货号登记"))
        "boatman" -> NpcKnowledgeView(id, "船工阿成，直率", listOf("潮水要涨", "检查明确货棚能找到人", "货棚不止一个") + if (s.rescued) listOf("玩家已告知第三号货棚，阿远已获救") else emptyList())
        else -> error("UNKNOWN_NPC")
    }
    fun npcAt(location: String) = mapOf("yard" to "keeper", "office" to "clerk", "dock" to "boatman")[location]
}
