package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*

object RuleAdapters {
    fun forRules(adapterId: String, adapterVersion: String): RuleAdapter = when {
        adapterId == "generic-d100-v1" && adapterVersion == "1" -> GenericD100()
        else -> throw IllegalArgumentException("UNKNOWN_RULE_ADAPTER")
    }
    fun forModule(module: ValidatedModule): RuleAdapter = forRules(module.rules.adapterId, module.rules.adapterVersion)
}
