package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.module.ExampleModules
import com.ai.assistance.operit.rpg.agent.rules.initialWorld
import com.ai.assistance.operit.rpg.agent.contract.KnowledgeEdge
import org.junit.Assert.*
import org.junit.Test

class WorldCanonicalTest {
 @Test fun twelveNpcOrderDoesNotChangeEqualityButFieldsDo(){
  val m=ExampleModules.all().first();val w=initialWorld(m,m.world.characters.first().id)
  assertEquals(12,w.npcs.size)
  val reversed=w.copy(npcs=w.npcs.reversed(),items=w.items.reversed(),facts=w.facts.reversed(),objectives=w.objectives.reversed())
  assertEquals(canonicalWorld(w),canonicalWorld(reversed))
  assertNotEquals(canonicalWorld(w),canonicalWorld(reversed.copy(npcs=reversed.npcs.mapIndexed{i,n->if(i==0)n.copy(status="busy")else n})))
 }
 @Test fun duplicateEntityIdsAndKnowledgeKeysAreRejected(){
  val m=ExampleModules.all().first();val w=initialWorld(m,m.world.characters.first().id)
  fun rejected(block:()->Unit){var rejected=false;try{block()}catch(_:IllegalArgumentException){rejected=true};assertTrue(rejected)}
  rejected{canonicalWorld(w.copy(npcs=w.npcs+w.npcs.first()))}
  rejected{canonicalWorld(w.copy(facts=w.facts+w.facts.first()))}
  val edge=KnowledgeEdge(w.npcs.first().id,w.facts.first().id,1,"heard","believed")
  rejected{canonicalWorld(w.copy(knowledge=listOf(edge,edge.copy(belief="doubted"))))}
 }
}
