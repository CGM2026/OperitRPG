package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*

/** Narrative effect validation and application shared by every rule adapter. `kinds` is the adapter's own allowed set. */
internal object NarrativeEffects {
    fun validate(e:Effect,w:WorldState,m:ValidatedModule,kinds:Set<String>) {
        require(e.type in m.rules.allowedEffects && e.type in kinds) { "UNKNOWN_EFFECT" }
        require(e.source.authority in setOf("module_rule","module_custom","temporary_ruling")) { "INVALID_EFFECT_SOURCE" }
        if(e.source.authority != "temporary_ruling" && e.source.authority != "generated") require(m.rules.evidence.any { it.source == e.source }) { "INVALID_EFFECT_SOURCE" }
        when(e.type) {
            "move" -> require(m.world.locations.any { it.id == e.targetId } && m.world.locations.single { it.id == w.locationId }.exits.contains(e.targetId)) { "UNREACHABLE" }
            "resource" -> require(e.targetId == "player" && e.key in m.rules.resourceBounds && e.amount != null && e.amount in -1000..1000)
            "transfer_item" -> require(w.items.any { it.id == e.targetId } && (e.value == "player" || w.npcs.any { it.id == e.value && it.locationId == w.locationId }))
            "reveal_fact" -> require(w.facts.any { it.id == e.targetId })
            "objective" -> require(w.objectives.any { it.id == e.targetId } && e.value in setOf("active","complete","failed"))
            "npc_status" -> require(w.npcs.any { it.id == e.targetId && it.locationId == w.locationId } && e.value in setOf("idle","helpful","hostile","busy","injured","departed"))
            "npc_relation" -> require(w.npcs.any { it.id == e.targetId && it.locationId == w.locationId } && e.key in setOf("trust","fear","respect") && e.amount != null && e.amount in -20..20)
            "knowledge" -> require(w.npcs.any { it.id == e.targetId && it.locationId == w.locationId } && w.facts.any { it.id == e.value && it.visibility.audience == Audience.PUBLIC }) { "NPC_CANNOT_KNOW" }
            "time" -> require(e.targetId == "world" && e.amount != null && e.amount in 1..1440)
        }
    }
    fun apply(w:WorldState,e:Effect,seq:Long,module:ValidatedModule):WorldState = when(e.type) {
        "move" -> w.copy(locationId=e.targetId)
        "resource" -> { val key=requireNotNull(e.key); val value=w.resources.getValue(key)+requireNotNull(e.amount); val bounds=module.rules.resourceBounds.getValue(key); require(value in bounds.min..bounds.max) { "RESOURCE_BOUNDS" }; w.copy(resources=w.resources+(key to value)) }
        "transfer_item" -> w.copy(items=w.items.map { if(it.id==e.targetId)it.copy(ownerId=requireNotNull(e.value)) else it })
        "reveal_fact" -> w.copy(facts=w.facts.map { if(it.id==e.targetId)it.copy(visibility=Visibility(Audience.PUBLIC)) else it })
        "objective" -> w.copy(objectives=w.objectives.map { if(it.id==e.targetId)it.copy(status=requireNotNull(e.value)) else it })
        "npc_status" -> w.copy(npcs=w.npcs.map { if(it.id==e.targetId)it.copy(status=requireNotNull(e.value)) else it })
        "npc_relation" -> w.copy(npcs=w.npcs.map { if(it.id==e.targetId) { val key=requireNotNull(e.key); val value=(it.relationships[key]?:0)+requireNotNull(e.amount); require(value in -100..100); it.copy(relationships=it.relationships+(key to value)) } else it })
        "knowledge" -> { val edge=KnowledgeEdge(e.targetId,requireNotNull(e.value),seq,"heard","believed"); w.copy(knowledge=w.knowledge.filterNot { it.knowerId==edge.knowerId && it.factId==edge.factId }+edge) }
        "time" -> w.copy(gameTime=Math.addExact(w.gameTime,requireNotNull(e.amount).toLong()))
        else -> error("UNKNOWN_EFFECT")
    }
}
