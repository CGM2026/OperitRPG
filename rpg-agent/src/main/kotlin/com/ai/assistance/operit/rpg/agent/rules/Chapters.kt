package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*

/**
 * Module chapters: the first chapter opens a campaign, and later chapters advance only when the world after a
 * committed event satisfies their `enterWhen`. Advances are recorded as [ChapterMark]s so replay never evaluates
 * conditions again.
 */
object Chapters {
    const val SUMMARY_KIND = "CHAPTER_SUMMARY"

    class Advancement(val chapterId: String?, val marks: List<ChapterMark>)

    /** The chapter a newly created campaign starts in; null when the module declares no chapters. */
    fun initialId(rules: ModuleRules): String? = rules.chapters.firstOrNull()?.id

    /** Title of the campaign's current chapter, or null when chapters are not enabled. */
    fun currentTitle(campaign: Campaign, rules: ModuleRules): String? {
        val id = campaign.chapterId ?: return null
        return requireNotNull(rules.chapters.firstOrNull { it.id == id }) { "UNKNOWN_CHAPTER" }.title
    }

    /**
     * Advances through consecutive chapters whose conditions all hold in [world] (the state after the committed
     * event). Chapters only move forward; the returned marks are the chapters entered by this settlement.
     */
    fun advance(campaign: Campaign, world: WorldState, rules: ModuleRules): Advancement {
        if (rules.chapters.isEmpty()) return Advancement(null, emptyList())
        val from = requireNotNull(campaign.chapterId) { "UNKNOWN_CHAPTER" }
        var index = rules.chapters.indexOfFirst { it.id == from }
        require(index >= 0) { "UNKNOWN_CHAPTER" }
        val generic = GenericD100()
        val marks = mutableListOf<ChapterMark>()
        while (index + 1 < rules.chapters.size) {
            val next = rules.chapters[index + 1]
            if (!next.enterWhen.all { generic.condition(it, world) }) break
            index++
            marks += ChapterMark(next.id, next.title, index + 1)
        }
        return Advancement(rules.chapters[index].id, marks)
    }

    /** Event-sourced chapter id: the last recorded mark wins; without marks the campaign stays in its first chapter. */
    fun replayId(events: List<WorldEvent>, rules: ModuleRules): String? =
        events.fold(initialId(rules)) { id, event -> event.chapters.lastOrNull()?.chapterId ?: id }
}
