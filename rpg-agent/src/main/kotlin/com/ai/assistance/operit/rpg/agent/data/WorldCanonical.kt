package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.contract.WorldState

/** Entity collections are keyed sets; order is not persisted world meaning. Reject duplicates
 * before sorting so backup verification still compares every field of every distinct entity. */
fun canonicalWorld(world:WorldState):WorldState {
 fun unique(ids:List<String>) { require(ids.distinct().size==ids.size){"DUPLICATE_WORLD_ENTITY"} }
 unique(world.npcs.map{it.id});unique(world.items.map{it.id});unique(world.facts.map{it.id})
 unique(world.objectives.map{it.id});unique(world.generated.map{it.id})
 require(world.knowledge.map{it.knowerId to it.factId}.distinct().size==world.knowledge.size){"DUPLICATE_KNOWLEDGE_EDGE"}
 return world.copy(npcs=world.npcs.sortedBy{it.id},items=world.items.sortedBy{it.id},facts=world.facts.sortedBy{it.id},objectives=world.objectives.sortedBy{it.id},generated=world.generated.sortedBy{it.id},knowledge=world.knowledge.sortedWith(compareBy({it.knowerId},{it.factId})))
}
