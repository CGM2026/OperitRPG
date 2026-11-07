package com.ai.assistance.operit.rpg.ui.play

import com.ai.assistance.operit.rpg.agent.ui.RollUi
import org.junit.Assert.assertEquals
import org.junit.Test

class RollUiTest {
    private fun roll(success: Boolean?) = RollUi(
        purpose = "攀爬",
        expression = "1d20+5",
        faces = listOf(14),
        modifier = 5,
        total = 19,
        target = 15,
        success = success,
    )

    @Test
    fun targetAndSuccessShowComparisonAndSuccess() {
        assertEquals("攀爬 19 对 15 · 成功", roll(true).summary)
    }

    @Test
    fun targetAndFailureShowComparisonAndFailure() {
        assertEquals("攀爬 19 对 15 · 失败", roll(false).summary)
    }

    @Test
    fun noTargetShowsOnlyPurposeAndTotal() {
        val roll = RollUi("察言观色", "1d20+2", listOf(10), 2, 12, null, null)
        assertEquals("察言观色 12", roll.summary)
    }

    @Test
    fun detailShowsFacesModifierAndExpression() {
        assertEquals("骰面 14 · 加值 +5 · 1d20+5", roll(true).detail)
    }
}
