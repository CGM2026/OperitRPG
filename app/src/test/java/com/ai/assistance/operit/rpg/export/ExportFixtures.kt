package com.ai.assistance.operit.rpg.export

import com.ai.assistance.operit.rpg.agent.contract.*
import java.time.LocalDateTime

internal val exportSource = SourceRef("module/sources/rules.md", "hash", "check", "module_rule")

internal fun exportModule(
    chapters: List<ChapterDefinition> = emptyList(),
    schedule: List<ScheduledEvent> = emptyList(),
    clock: ClockSpec? = null,
): ValidatedModule = ValidatedModule(
    manifest = ModuleManifest(1, "test", "1.0.0", "江湖测试", "test", "简介", 1, "generic-d100-v1", "1", emptyList()),
    contentHash = "hash",
    world = ModuleWorld(
        startLocationId = "room",
        characters = listOf(CharacterTemplate("hero", "少侠", emptyMap(), emptyMap())),
        locations = listOf(Location("room", "客栈", "", emptyList())),
        npcs = listOf(NpcState("npc", "掌柜", "room", "idle", "谨慎", "守店")),
        items = emptyList(),
        facts = emptyList(),
        objectives = emptyList(),
        actions = emptyList(),
        policy = GenerationPolicy(emptySet(), 0, emptySet()),
        schedule = schedule,
        clock = clock,
    ),
    rules = ModuleRules(
        adapterId = "generic-d100-v1",
        adapterVersion = "1",
        skills = emptySet(),
        resourceBounds = emptyMap(),
        difficulties = emptyMap(),
        allowedEffects = emptySet(),
        evidence = emptyList(),
        timeUnit = "分钟",
        chapters = chapters,
    ),
    sources = emptyMap(),
)

internal fun exportCampaign(
    title: String,
    world: WorldState,
    chapterId: String? = null,
): Campaign = Campaign(
    id = "campaign",
    title = title,
    module = VersionRef("test", "1.0.0", "hash"),
    rules = VersionRef("generic-d100-v1", "1", "hash"),
    revision = 0,
    lastEventSeq = 0,
    world = world,
    allowGeneration = false,
    modelFingerprint = "",
    narratorFingerprint = "",
    updatedAt = 0,
    chapterId = chapterId,
)

internal fun exportWorld(
    facts: List<Fact> = emptyList(),
    knowledge: List<KnowledgeEdge> = emptyList(),
    npcs: List<NpcState> = listOf(NpcState("npc", "掌柜", "room", "idle", "谨慎", "守店")),
    gameTime: Long = 0,
): WorldState = WorldState(
    locationId = "room",
    resources = emptyMap(),
    skills = emptyMap(),
    npcs = npcs,
    items = emptyList(),
    facts = facts,
    objectives = emptyList(),
    knowledge = knowledge,
    gameTime = gameTime,
)

internal val exportTime: LocalDateTime = LocalDateTime.of(2026, 11, 8, 9, 30)
