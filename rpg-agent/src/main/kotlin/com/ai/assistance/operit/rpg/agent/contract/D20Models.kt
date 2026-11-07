package com.ai.assistance.operit.rpg.agent.contract

import kotlinx.serialization.Serializable

/** Module-supplied d20 rules. Every identifier (pool, counter, condition, card) comes from module data. */
@Serializable data class D20Rules(
    val attributes: List<String>,
    val skills: Map<String, String>,
    val dcTiers: Map<String, Int>,
    val pools: Map<String, PoolRule>,
    val counters: Map<String, CounterRule>,
    val conditions: Map<String, ConditionRule>,
    val cards: Map<String, CardRule>,
    val rest: RestRule,
    val saveSuccessNegatesPoolLoss: Boolean,
    val defeatPoolIds: Set<String>,
    val concentrationAttribute: String,
)
@Serializable data class PoolRule(
    val label: String,
    val perRoundLossCap: Int? = null,
    val capGroup: String? = null,
    val damageSourceOnly: Set<String> = emptySet(),
    val atZeroCondition: String? = null,
    val atZeroDuration: DurationSpec? = null,
    val singleHitCannotEmptyFromFull: Boolean = false,
    val restoreOn: String,
)
@Serializable data class CounterRule(val label: String, val restoreOn: String)
@Serializable data class ConditionRule(
    val label: String,
    val text: String,
    val blocksActions: Boolean = false,
    val speedZero: Boolean = false,
    val attackDisadvantage: Boolean = false,
    val attackedAdvantage: Boolean = false,
    val autoFailSaves: Set<String> = emptySet(),
    val endsOnDamage: Boolean = false,
)
@Serializable data class RestRule(val shortMinutes: Int, val longMinutes: Int, val hitPoolId: String)
@Serializable data class DurationSpec(val unit: String, val value: Int = 1)

@Serializable data class CardRule(
    val label: String,
    val text: String,
    val timing: String,
    val isSpell: Boolean,
    val slotLevel: Int? = null,
    val counterCost: Map<String, Int> = emptyMap(),
    val concentration: Boolean = false,
    val maxTargets: Int = 1,
    val resolution: String,
    val keyAttribute: String? = null,
    val saveAttribute: String? = null,
    val saveAdvantageWhenHostile: Boolean = false,
    val contestSkill: String? = null,
    val contestDefenseSkills: Set<String> = emptySet(),
    val tieGoesToActor: Boolean = false,
    val requiresTargetCondition: Set<String> = emptySet(),
    val requiresActorNotCondition: Set<String> = emptySet(),
    val onSuccess: List<CardEffect> = emptyList(),
    val onFailure: List<CardEffect> = emptyList(),
    val onTargetSaveSuccessToActor: List<CardEffect> = emptyList(),
    val repeatSave: Boolean = false,
)
@Serializable data class CardEffect(
    val type: String,
    val target: String,
    val pool: String? = null,
    val counter: String? = null,
    val dice: String? = null,
    val amount: Int? = null,
    val condition: String? = null,
    val duration: DurationSpec? = null,
    val tag: String,
    val ifTargetHasCondition: String? = null,
)

@Serializable data class D20Combatant(
    val id: String,
    val name: String,
    val level: Int,
    val scores: Map<String, Int>,
    val proficiencyBonus: Int,
    val saveProficiencies: Set<String>,
    val skillRanks: Map<String, Int>,
    val armorClass: Int,
    val pools: Map<String, PoolValue>,
    val slots: Map<Int, PoolValue>,
    val slotRestoreOn: String,
    val counters: Map<String, PoolValue>,
    val cards: Set<String>,
    val weaponAttacks: Map<String, WeaponAttack>,
    val conditions: List<ActiveCondition> = emptyList(),
    val concentration: ActiveConcentration? = null,
)
@Serializable data class PoolValue(val current: Int, val max: Int)
@Serializable data class WeaponAttack(val label: String, val attribute: String, val proficient: Boolean, val damage: String, val tag: String = "weapon")
@Serializable data class ActiveCondition(val id: String, val sourceId: String, val sourceCard: String?, val expiresRound: Long?, val expiresMinute: Long?, val repeatSave: RepeatSave? = null)
@Serializable data class RepeatSave(val attribute: String, val dc: Int)
@Serializable data class ActiveConcentration(val cardId: String, val targetIds: List<String>, val expiresMinute: Long?)

@Serializable data class D20State(
    val combatants: Map<String, D20Combatant>,
    val round: Long,
    val roundLosses: Map<String, Int>,
)

@Serializable data class D20Action(
    val kind: String,
    val actorId: String,
    val targetIds: List<String> = emptyList(),
    val skill: String? = null,
    val attribute: String? = null,
    val dcTier: String? = null,
    val weaponId: String? = null,
    val cardId: String? = null,
    val slotLevel: Int? = null,
    val advantage: Int = 0,
    val advantageReason: String? = null,
    val restType: String? = null,
)
@Serializable data class RollRecord(val actorId: String, val purpose: String, val expression: String, val faces: List<Int>, val modifier: Int, val total: Int, val target: Int?, val success: Boolean?)
@Serializable data class D20Change(val type: String, val combatantId: String, val key: String? = null, val delta: Int? = null, val condition: ActiveCondition? = null, val value: String? = null)

/** Public view of a present NPC: name and condition labels only. */
@Serializable data class D20VisibleNpc(val name: String, val conditions: List<String>)
/** A module action that needs a d20 skill check: the player rolls [skill] against [dcTier]; success and failure effects follow the roll. */
@Serializable data class ActionCheck(val skill: String, val dcTier: String)
