package com.ai.assistance.operit.rpg.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable data class ScenarioDefinition(
    val id: String, val version: String, val rulesVersion: String,
    val locations: List<String>, val npcs: List<String>, val clues: List<String>,
    val actions: List<String>, val internalTruth: String, val deadline: Int, val tideWarningTick: Int
) {
    companion object {
        fun load(json: String): ScenarioDefinition = RpgJson.instance.decodeFromString<ScenarioDefinition>(json).also {
            require(it.id == "rainy-dock-v1" && it.version == "1.0.0" && it.rulesVersion == "prototype-d100-v1")
            require(it.locations.toSet() == RainyDock.locations.keys && it.locations.distinct().size == it.locations.size)
            require(it.npcs.toSet() == setOf("keeper", "clerk", "boatman") && it.npcs.size == 3)
            require(it.clues.toSet() == setOf("c1", "c2", "c3") && it.clues.size == 3)
            require(it.actions == RainyDock.actions.map { action -> action.id })
            require(it.deadline == 12 && it.tideWarningTick == 8 && it.internalTruth.isNotBlank())
            require(RainyDock.connections.all { entry -> entry.key in it.locations && entry.value.all { id -> id in it.locations } })
        }
    }
}
