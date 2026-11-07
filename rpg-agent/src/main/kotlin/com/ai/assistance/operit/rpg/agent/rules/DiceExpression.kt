package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.DiceSource

data class DiceOutcome(val expression: String, val faces: List<Int>, val modifier: Int, val total: Int)

object DiceExpression {
    val sidesAllowed = setOf(4, 6, 8, 10, 12, 20, 100)
    private val dice = Regex("([0-9]+)d([0-9]+)(?:([+-])([0-9]+))?")
    private val constant = Regex("-?[0-9]+")
    fun roll(expression: String, source: DiceSource): DiceOutcome {
        dice.matchEntire(expression)?.let { m ->
            val count = m.groupValues[1].toIntOrNull(); val sides = m.groupValues[2].toIntOrNull()
            val magnitude = if (m.groupValues[4].isEmpty()) 0 else m.groupValues[4].toIntOrNull()
            require(count != null && sides != null && magnitude != null && count in 1..20 && sides in sidesAllowed && magnitude <= 50) { "INVALID_DICE" }
            val modifier = if (m.groupValues[3] == "-") -magnitude else magnitude
            val faces = List(count) { source.roll(sides).also { face -> require(face in 1..sides) { "INVALID_DICE" } } }
            return DiceOutcome(expression, faces, modifier, faces.sum() + modifier)
        }
        require(constant.matches(expression)) { "INVALID_DICE" }
        val value = expression.toIntOrNull()
        require(value != null && value in -50..50) { "INVALID_DICE" }
        return DiceOutcome(expression, emptyList(), value, value)
    }
}
