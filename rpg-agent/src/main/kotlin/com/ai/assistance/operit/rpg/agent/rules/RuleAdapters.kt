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

/** Chapter declarations are optional; when present the first chapter always opens the campaign and every later chapter must gate itself. */
fun validateChapters(rules: ModuleRules, world: WorldState, module: ValidatedModule) {
    if (rules.chapters.isEmpty()) return
    require(rules.chapters.map { it.id }.distinct().size == rules.chapters.size && rules.chapters.all { it.id.isNotBlank() && it.title.isNotBlank() }) { "INVALID_CHAPTER" }
    require(rules.chapters.first().enterWhen.isEmpty() && rules.chapters.drop(1).all { it.enterWhen.isNotEmpty() }) { "INVALID_CHAPTER" }
    val generic = GenericD100()
    rules.chapters.forEach { chapter -> chapter.enterWhen.forEach { generic.validateCondition(it, world, module) } }
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
