package com.ai.assistance.operit.rpg.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.ai.assistance.operit.rpg.ui.paper.PaperTheme
import com.ai.assistance.operit.rpg.ui.paper.paperTone

/** Narrative body text stays serif; UI text remains the system sans-serif. */
val NarrationFontFamily = FontFamily.Serif

private const val PAPER_PREFERENCES = "rpg-agent-ui"
private const val TONE_KEY = "tone"

@Composable
fun D20Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PAPER_PREFERENCES, Context.MODE_PRIVATE)
    }
    val toneState = remember {
        mutableStateOf(preferences.getString(TONE_KEY, "sage") ?: "sage")
    }
    val setTone: (String) -> Unit = remember(preferences) {
        { id ->
            preferences.edit().putString(TONE_KEY, id).apply()
            toneState.value = id
        }
    }
    PaperTheme(tone = paperTone(toneState.value), setTone = setTone, content = content)
}
