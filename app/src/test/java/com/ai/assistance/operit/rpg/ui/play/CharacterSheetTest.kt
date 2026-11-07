package com.ai.assistance.operit.rpg.ui.play

import com.ai.assistance.operit.rpg.agent.contract.ActiveCondition
import com.ai.assistance.operit.rpg.agent.contract.ConditionRule
import com.ai.assistance.operit.rpg.agent.contract.CounterRule
import com.ai.assistance.operit.rpg.agent.contract.D20Combatant
import com.ai.assistance.operit.rpg.agent.contract.D20Rules
import com.ai.assistance.operit.rpg.agent.contract.D20State
import com.ai.assistance.operit.rpg.agent.contract.PoolRule
import com.ai.assistance.operit.rpg.agent.contract.PoolValue
import com.ai.assistance.operit.rpg.agent.contract.RestRule
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterSheetTest {
    private val rules = D20Rules(
        attributes = listOf("might"),
        skills = emptyMap(),
        dcTiers = emptyMap(),
        pools = mapOf(
            "hp" to PoolRule("气血", restoreOn = "long"),
            "poise" to PoolRule("自制", restoreOn = "short"),
            "will" to PoolRule("意志", restoreOn = "short"),
        ),
        counters = mapOf("qi" to CounterRule("真气", "short")),
        conditions = mapOf("bound" to ConditionRule("受缚", "无法移动")),
        cards = emptyMap(),
        rest = RestRule(10, 480, "hp"),
        saveSuccessNegatesPoolLoss = true,
        defeatPoolIds = setOf("hp"),
        concentrationAttribute = "might",
    )

    private val hero = D20Combatant(
        id = "hero",
        name = "侠客",
        level = 1,
        scores = mapOf("might" to 14),
        proficiencyBonus = 2,
        saveProficiencies = emptySet(),
        skillRanks = emptyMap(),
        armorClass = 12,
        pools = mapOf(
            "hp" to PoolValue(12, 20),
            "poise" to PoolValue(4, 10),
            "will" to PoolValue(9, 10),
        ),
        slots = mapOf(1 to PoolValue(1, 2)),
        slotRestoreOn = "long",
        counters = mapOf("qi" to PoolValue(2, 3)),
        cards = emptySet(),
        weaponAttacks = emptyMap(),
        conditions = listOf(ActiveCondition("bound", "foe", null, expiresRound = 5, expiresMinute = null)),
    )

    @Test
    fun poolsUseModuleLabelsAndCombatantValues() {
        val state = D20State(mapOf("hero" to hero), round = 3, roundLosses = emptyMap())

        val sheet = projectCharacterSheet(hero, rules, state, gameTime = 40, timeUnit = "分", items = emptyList(), facts = emptyList(), objectives = emptyList())

        assertEquals("侠客", sheet.name)
        assertEquals(listOf("气血", "自制", "意志"), sheet.pools.map { it.label })
        assertEquals(listOf(12, 4, 9), sheet.pools.map { it.current })
        assertEquals(listOf(20, 10, 10), sheet.pools.map { it.max })
        assertEquals(listOf("真气"), sheet.counters.map { it.label })
        assertEquals(listOf(2), sheet.counters.map { it.current })
        assertEquals(listOf(3), sheet.counters.map { it.max })
        assertEquals(listOf("受缚" to "剩余 2 轮"), sheet.conditions.map { it.label to it.remaining })
    }
}
