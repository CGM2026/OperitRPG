package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*

/** Display time for the 时辰 row and the narrator. Without a clock spec the plain minute count is shown. */
fun clockText(spec: ClockSpec?, gameTime: Long): String {
    if (spec == null) return "$gameTime 分钟"
    val unit = Math.addExact(spec.labels.indexOf(spec.startLabel).toLong(), gameTime / spec.minutesPerUnit)
    val size = spec.labels.size
    return "第${unit / size + 1}日 ${spec.labels[(unit % size).toInt()]}"
}

/**
 * Host schedule shared by every rule adapter. Runs after the player's action is settled, at the resulting gameTime,
 * and returns the recorded transitions; replay applies those records and never evaluates conditions again.
 * Schedule effects run with host authority, so they are validated here and never accepted from player proposals.
 */
internal object Scheduler {
    /** Fire/skip decisions allowed in one settlement. More means the module data is looping. */
    const val LIMIT = 64
    const val MAX_EVENTS = 256
    val states = setOf("pending", "armed", "fired", "skipped", "cancelled")
    private val open = setOf("pending", "armed")
    val effectKinds = setOf("npc_move", "npc_status", "transfer_item", "knowledge", "objective", "set_flag", "reveal_fact")
    private val npcStatuses = setOf("idle", "helpful", "hostile", "busy", "injured", "departed")
    private val generic = GenericD100()

    class Outcome(val world: WorldState, val firings: List<ScheduledFiring>, val notices: List<String>)

    fun initialSchedule(world: ModuleWorld): Map<String, ScheduleStatus> = world.schedule.associate { it.id to ScheduleStatus("pending") }

    // ---------------------------------------------------------------- validation

    fun validateModule(module: ValidatedModule, initial: WorldState) {
        val w = module.world
        require(w.schedule.size <= MAX_EVENTS && w.initialFlags.size <= MAX_EVENTS && w.initialFlags.all { (k, v) -> validFlag(k, v) }) { "INVALID_SCHEDULE" }
        w.clock?.let { c ->
            require(c.labels.size in 1..48 && c.labels.distinct().size == c.labels.size && c.labels.all { it.isNotBlank() && it.length <= 20 }) { "INVALID_CLOCK" }
            require(c.startLabel in c.labels && c.minutesPerUnit in 1..100000) { "INVALID_CLOCK" }
        }
        w.schedule.forEach { s ->
            require(s.label.isNotBlank() && s.label.length <= 200 && s.effects.isNotEmpty() && s.effects.size <= 16 && s.delayMinutes in 0..100000) { "INVALID_SCHEDULE" }
            val notice = s.notice
            require(notice == null || (notice.isNotBlank() && notice.length <= 500)) { "INVALID_SCHEDULE" }
            val at = s.atMinute
            if (at != null) require(at >= 0 && s.delayMinutes == 0) { "INVALID_SCHEDULE" }
            else require(s.conditions.isNotEmpty()) { "INVALID_SCHEDULE" }
            val all = s.conditions + s.cancelIf
            require(all.sumOf { generic.count(it, 0) } <= 64) { "CONDITION_LIMIT" }
            all.forEach { generic.validateCondition(it, initial, module) }
            s.effects.forEach { validateEffect(it, initial, module) }
        }
    }

    private fun validFlag(key: String, value: String) = key.isNotBlank() && key.length <= 64 && value.length <= 200

    fun validateState(world: WorldState, module: ValidatedModule) {
        require(world.schedule.keys == module.world.schedule.map { it.id }.toSet()) { "INVALID_SCHEDULE_STATE" }
        require(world.schedule.values.all { it.state in states && (it.state != "armed" || it.fireAt != null) }) { "INVALID_SCHEDULE_STATE" }
        require(world.flags.size <= MAX_EVENTS && world.flags.all { (k, v) -> validFlag(k, v) }) { "INVALID_FLAGS" }
    }

    fun validateEffect(e: Effect, w: WorldState, m: ValidatedModule) {
        require(e.type in effectKinds) { "UNKNOWN_EFFECT" }
        require(e.source.authority == "module_rule" && m.rules.evidence.any { it.source == e.source }) { "INVALID_EFFECT_SOURCE" }
        fun npc(id: String?) = w.npcs.any { it.id == id }
        fun location(id: String?) = m.world.locations.any { it.id == id }
        val ok = when (e.type) {
            "npc_move" -> npc(e.targetId) && location(e.value)
            "npc_status" -> npc(e.targetId) && e.value in npcStatuses
            "transfer_item" -> w.items.any { it.id == e.targetId } && (e.value == "player" || npc(e.value) || location(e.value))
            "knowledge" -> npc(e.targetId) && w.facts.any { it.id == e.value }
            "objective" -> w.objectives.any { it.id == e.targetId } && e.value in setOf("active", "complete", "failed")
            "set_flag" -> e.targetId == "world" && e.key != null && e.value != null && validFlag(e.key, e.value)
            "reveal_fact" -> w.facts.any { it.id == e.targetId }
            else -> false
        }
        require(ok) { "INVALID_SCHEDULE_EFFECT" }
    }

    private fun applyEffect(w: WorldState, e: Effect, seq: Long, m: ValidatedModule): WorldState = when (e.type) {
        "npc_move" -> w.copy(npcs = w.npcs.map { if (it.id == e.targetId) it.copy(locationId = requireNotNull(e.value)) else it })
        "set_flag" -> w.copy(flags = w.flags + (requireNotNull(e.key) to requireNotNull(e.value)))
        else -> NarrativeEffects.apply(w, e, seq, m)
    }

    // ---------------------------------------------------------------- recorded transitions

    /** The single place a transition changes state; used both online and on replay so they cannot diverge. */
    private fun transition(w: WorldState, f: ScheduledFiring, seq: Long, m: ValidatedModule): WorldState {
        val def = requireNotNull(m.world.schedule.firstOrNull { it.id == f.scheduleId }) { "UNKNOWN_SCHEDULE" }
        val old = requireNotNull(w.schedule[f.scheduleId]) { "UNKNOWN_SCHEDULE" }
        val allowed = when (f.state) {
            "armed" -> old.state == "pending" && def.atMinute == null
            "fired" -> old.state == "armed" || (old.state == "pending" && def.atMinute != null)
            "skipped" -> old.state == "pending" && def.atMinute != null
            "cancelled" -> old.state in open
            else -> false
        }
        require(allowed) { "INVALID_SCHEDULE_TRANSITION" }
        require((f.state == "armed") == (f.fireAt != null) && (f.state == "fired" || f.effects.isEmpty())) { "INVALID_SCHEDULE_TRANSITION" }
        var next = w
        f.effects.forEach { validateEffect(it, next, m); next = applyEffect(next, it, seq, m) }
        val status = if (f.state == "armed") ScheduleStatus("armed", f.fireAt) else old.copy(state = f.state)
        return next.copy(schedule = next.schedule + (f.scheduleId to status))
    }

    fun apply(w: WorldState, firings: List<ScheduledFiring>, seq: Long, m: ValidatedModule): WorldState = firings.fold(w) { acc, f -> transition(acc, f, seq, m) }

    // ---------------------------------------------------------------- evaluation

    /** Repeats cancel, arm, fire/skip until nothing is left to process at the current gameTime. gameTime never moves inside the loop. */
    fun run(start: WorldState, module: ValidatedModule, seq: Long): Outcome {
        val events = module.world.schedule.sortedBy { it.id }
        if (events.isEmpty()) return Outcome(start, emptyList(), emptyList())
        val now = start.gameTime
        var w = start
        val firings = mutableListOf<ScheduledFiring>()
        val notices = mutableListOf<String>()
        var handled = 0
        fun state(s: ScheduledEvent): ScheduleStatus = requireNotNull(w.schedule[s.id]) { "INVALID_SCHEDULE_STATE" }
        fun holds(list: List<Condition>) = list.all { generic.condition(it, w) }
        fun record(f: ScheduledFiring) { firings += f; w = transition(w, f, seq, module) }
        fun dueAt(s: ScheduledEvent): Long? {
            val st = state(s)
            val time = if (st.state == "pending") s.atMinute else if (st.state == "armed") st.fireAt else null
            return if (time != null && time <= now) time else null
        }
        while (true) {
            val cancel = events.firstOrNull { s -> state(s).state in open && s.cancelIf.any { generic.condition(it, w) } }
            if (cancel != null) { record(ScheduledFiring(cancel.id, "cancelled", emptyList())); continue }
            val arm = events.firstOrNull { s -> s.atMinute == null && state(s).state == "pending" && holds(s.conditions) }
            if (arm != null) { record(ScheduledFiring(arm.id, "armed", emptyList(), Math.addExact(now, arm.delayMinutes.toLong()))); continue }
            val next = events.mapNotNull { s -> dueAt(s)?.let { s to it } }.minWithOrNull(compareBy<Pair<ScheduledEvent, Long>>({ it.second }, { it.first.id })) ?: break
            val s = next.first
            handled++
            require(handled <= LIMIT) { "SCHEDULE_LOOP" }
            // A timed event checks its conditions at its time; an armed conditional event was already decided when it armed.
            if (s.atMinute != null && !holds(s.conditions)) record(ScheduledFiring(s.id, "skipped", emptyList()))
            else { record(ScheduledFiring(s.id, "fired", s.effects)); s.notice?.let { notices += it } }
        }
        return Outcome(w, firings, notices)
    }

    /** Planning aid: the next [limit] events still open, by time; conditional events without a time come last. */
    fun upcoming(world: WorldState, module: ValidatedModule, limit: Int): List<UpcomingSchedule> =
        module.world.schedule.mapNotNull { s ->
            val st = requireNotNull(world.schedule[s.id]) { "INVALID_SCHEDULE_STATE" }
            if (st.state !in open) null else UpcomingSchedule(s.id, s.label, st.state, if (st.state == "armed") st.fireAt else s.atMinute)
        }.sortedWith(compareBy<UpcomingSchedule>({ it.minute ?: Long.MAX_VALUE }, { it.id })).take(limit)
}
