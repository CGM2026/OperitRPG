package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*

/** Content levels are optional; when declared, labels and guidelines must be non-blank and the default must index one of them. */
fun validateContentLevels(rules: ModuleRules) {
    if (rules.contentLevels.isEmpty()) {
        require(rules.defaultContentLevel == 0) { "INVALID_CONTENT_LEVEL" }
        return
    }
    require(rules.contentLevels.size <= 10 && rules.contentLevels.all { it.label.isNotBlank() && it.narratorGuideline.isNotBlank() && it.label.length <= 40 && it.narratorGuideline.length <= 2000 }) { "INVALID_CONTENT_LEVEL" }
    require(rules.defaultContentLevel in rules.contentLevels.indices) { "INVALID_CONTENT_LEVEL" }
}

object RuleAdapters {
    fun forRules(adapterId: String, adapterVersion: String): RuleAdapter = when {
        adapterId == "generic-d100-v1" && adapterVersion == "1" -> GenericD100()
        adapterId == WuxiaD20.ID && adapterVersion == "1" -> WuxiaD20()
        else -> throw IllegalArgumentException("UNKNOWN_RULE_ADAPTER")
    }
    /** Event schema version each adapter writes and accepts. */
    fun eventVersion(adapterId: String): Int = when (adapterId) {
        "generic-d100-v1" -> 1
        WuxiaD20.ID -> 2
        else -> throw IllegalArgumentException("UNKNOWN_RULE_ADAPTER")
    }
    fun forModule(module: ValidatedModule): RuleAdapter = forRules(module.rules.adapterId, module.rules.adapterVersion)
}
