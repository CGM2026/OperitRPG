package com.ai.assistance.operit.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.rpg.agent.ui.D20AgentRoute
import com.ai.assistance.operit.rpg.ui.RpgModelSettingsScreen
import com.ai.assistance.operit.rpg.ui.theme.D20Theme

/** Keep the published launcher component so installed shortcuts retain their identity. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            D20Theme {
                var settings by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (settings) RpgModelSettingsScreen { settings = false }
                    else D20AgentRoute(onModelSettings = { settings = true })
                }
            }
        }
    }
}
