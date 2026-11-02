package com.ai.assistance.operit.rpg.integration

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.rpg.agent.ui.D20AgentRoute
import com.ai.assistance.operit.rpg.ui.DiceModelSettingsScreen

/** Old installs resume their selected old campaign; subsequent navigation uses an explicit engine. */
@Composable
fun D20Route() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("d20-navigation", 0) }
    var route by rememberSaveable { mutableStateOf(prefs.getString("engine", null)
        ?: if (context.getSharedPreferences("rpg-ui", 0).contains("campaign")) "legacy" else "longform") }
    var legacyId by rememberSaveable { mutableStateOf<String?>(null) }
    var legacyList by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    fun navigate(engine: String) { route = engine; prefs.edit().putString("engine", engine).apply() }
    when {
        settings -> DiceModelSettingsScreen { settings = false }
        route == "legacy" -> RpgRoute(onBackToHome = { navigate("longform") }, requestedCampaignId = legacyId, openList = legacyList)
        route == "longform" -> D20AgentRoute(onOpenLegacy = { id -> legacyId = id; legacyList = id == null; navigate("legacy") }, onModelSettings = { settings = true })
        else -> error("UNKNOWN_ENGINE_ROUTE")
    }
}
