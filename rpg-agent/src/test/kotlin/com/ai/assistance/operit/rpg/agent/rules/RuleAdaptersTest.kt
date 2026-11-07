package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.module.ExampleModules
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@Serializable private data class OldPlanningRuleIndex(val adapterId:String,val adapterVersion:String,val difficulties:Map<String,Int>,val allowedEffects:Set<String>,val ruleIds:List<String>)

class RuleAdaptersTest {
    @Test fun knownAdapterResolves() {
        assertIs<GenericD100>(RuleAdapters.forRules("generic-d100-v1","1"))
        val m=ExampleModules.all().first()
        assertIs<GenericD100>(RuleAdapters.forModule(m))
    }
    @Test fun unknownAdapterIsRejected() {
        assertEquals("UNKNOWN_RULE_ADAPTER",assertFailsWith<IllegalArgumentException> { RuleAdapters.forRules("generic-d100-v1","2") }.message)
        assertEquals("UNKNOWN_RULE_ADAPTER",assertFailsWith<IllegalArgumentException> { RuleAdapters.forRules("nope","1") }.message)
        val m=ExampleModules.all().first()
        assertEquals("UNKNOWN_RULE_ADAPTER",assertFailsWith<IllegalArgumentException> { RuleAdapters.forModule(m.copy(rules=m.rules.copy(adapterId="nope"))) }.message)
    }
    @Test fun planningRuleIndexMatchesOldSerializationByteForByte() {
        ExampleModules.all().forEach { m ->
            val old=OldPlanningRuleIndex(m.rules.adapterId,m.rules.adapterVersion,m.rules.difficulties,m.rules.allowedEffects,m.rules.evidence.map { it.ruleId })
            assertEquals(Json.encodeToString(old),Json.encodeToString(GenericD100().planningRuleIndex(m)))
        }
    }
    @Test fun eventVersionIsPerAdapter() {
        assertEquals(1,RuleAdapters.eventVersion("generic-d100-v1"))
        assertEquals(2,RuleAdapters.eventVersion(WuxiaD20.ID))
        assertEquals("UNKNOWN_RULE_ADAPTER",assertFailsWith<IllegalArgumentException> { RuleAdapters.eventVersion("nope") }.message)
    }
}
