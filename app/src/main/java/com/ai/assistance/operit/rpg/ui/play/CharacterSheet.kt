package com.ai.assistance.operit.rpg.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rpg.agent.ui.CharacterCounterUi
import com.ai.assistance.operit.rpg.agent.ui.CharacterConditionUi
import com.ai.assistance.operit.rpg.agent.ui.CharacterItemUi
import com.ai.assistance.operit.rpg.agent.ui.CharacterPoolUi
import com.ai.assistance.operit.rpg.agent.ui.CharacterSheetUi
import com.ai.assistance.operit.rpg.agent.ui.CharacterSlotUi
import com.ai.assistance.operit.rpg.agent.contract.D20Combatant
import com.ai.assistance.operit.rpg.agent.contract.D20Rules
import com.ai.assistance.operit.rpg.agent.contract.D20State
import com.ai.assistance.operit.rpg.agent.contract.Fact
import com.ai.assistance.operit.rpg.agent.contract.Item
import com.ai.assistance.operit.rpg.agent.contract.Objective
import com.ai.assistance.operit.rpg.ui.avatar.Avatar
import com.ai.assistance.operit.rpg.ui.avatar.AvatarSpec

/**
 * Pure projection of the player combatant. Names and maxima come from the module rules; a pool,
 * condition or counter that the module does not define is not shown.
 */
fun projectCharacterSheet(
    combatant: D20Combatant,
    rules: D20Rules,
    state: D20State,
    gameTime: Long,
    timeUnit: String,
    items: List<Item>,
    facts: List<Fact>,
    objectives: List<Objective>,
): CharacterSheetUi = CharacterSheetUi(
    name = combatant.name,
    pools = combatant.pools.mapNotNull { (id, value) ->
        rules.pools[id]?.let { CharacterPoolUi(id, it.label, value.current, value.max) }
    },
    conditions = combatant.conditions.mapNotNull { condition ->
        rules.conditions[condition.id]?.let { rule ->
            CharacterConditionUi(condition.id, rule.label, conditionRemaining(condition.expiresRound, condition.expiresMinute, state.round, gameTime, timeUnit))
        }
    },
    slots = combatant.slots.entries.sortedBy { it.key }.map { (level, value) ->
        CharacterSlotUi(level, value.current, value.max)
    },
    counters = combatant.counters.mapNotNull { (id, value) ->
        rules.counters[id]?.let { CharacterCounterUi(id, it.label, value.current, value.max) }
    },
    items = items.map { CharacterItemUi(it.title, it.quantity) },
    facts = facts.map { it.text },
    objectives = objectives.map { "${it.title} · ${statusText(it.status)}" },
)

private fun conditionRemaining(expiresRound: Long?, expiresMinute: Long?, round: Long, gameTime: Long, timeUnit: String): String? =
    when {
        expiresRound != null -> "剩余 ${(expiresRound - round).coerceAtLeast(0)} 轮"
        expiresMinute != null -> "剩余 ${(expiresMinute - gameTime).coerceAtLeast(0)} $timeUnit"
        else -> null
    }

private fun statusText(status: String) = when (status) {
    "active" -> "进行中"
    "complete" -> "已完成"
    else -> status
}

@Composable
fun CharacterSheet(sheet: CharacterSheetUi, avatar: AvatarSpec, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(avatar, 64.dp)
            Text(sheet.name, style = MaterialTheme.typography.titleLarge)
        }
        if (sheet.pools.isNotEmpty()) {
            SheetSection("资源") {
                sheet.pools.forEach { pool -> PoolLine(pool) }
            }
        }
        if (sheet.conditions.isNotEmpty()) {
            SheetSection("状态") {
                sheet.conditions.forEach { condition ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(condition.label, style = MaterialTheme.typography.bodyMedium)
                        condition.remaining?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (sheet.slots.isNotEmpty()) {
            SheetSection("法术位") {
                sheet.slots.forEach { slot -> ResourceLine("${slot.level} 环", slot.current, slot.max) }
            }
        }
        if (sheet.counters.isNotEmpty()) {
            SheetSection("次数") {
                sheet.counters.forEach { counter -> ResourceLine(counter.label, counter.current, counter.max) }
            }
        }
        if (sheet.items.isNotEmpty()) {
            SheetSection("物品") {
                sheet.items.forEach { item ->
                    Text(if (item.quantity > 1) "${item.title} × ${item.quantity}" else item.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (sheet.facts.isNotEmpty()) {
            SheetSection("线索") {
                sheet.facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (sheet.objectives.isNotEmpty()) {
            SheetSection("目标") {
                sheet.objectives.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun PoolLine(pool: CharacterPoolUi) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(pool.label, style = MaterialTheme.typography.bodyMedium)
            Text("${pool.current} / ${pool.max}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { if (pool.max > 0) pool.current.toFloat() / pool.max else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
    }
}

@Composable
private fun ResourceLine(label: String, current: Int, max: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("$current / $max", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
