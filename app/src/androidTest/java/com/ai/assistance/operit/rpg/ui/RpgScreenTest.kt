package com.ai.assistance.operit.rpg.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Runs on an Android device/emulator; captured PNGs stay in the test app's own files. */
class RpgScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finishedCampaignStillAcceptsRulesAndReview() {
        val controller = TestController(play(ending = "阿远已安全离开码头。"))
        compose.setContent { RpgScreen(controller, {}) }
        compose.onNode(hasSetTextAction()).performTextInput("请回顾找到阿远的经过")
        compose.onNodeWithText("发送").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("请回顾找到阿远的经过", controller.submitted) }
        capture("finished-review")
    }

    @Test fun pendingTurnDisablesNewSubmissionWhileCluesRemainReadableOnSmallScreen() {
        val controller = TestController(play(canAct = false).copy(
            clues = listOf(RpgDetail("c1", "阿远的便条", "我去处理货号 K17，稍后回来。")),
            pendingNotice = "本轮结果已保存，请完成叙述后继续。"))
        compose.setContent { Box(Modifier.requiredSize(320.dp, 480.dp)) { RpgScreen(controller, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("再去办公室")
        compose.onNodeWithText("发送").assertIsNotEnabled()
        capture("small-screen-keyboard")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("线索 1").performClick()
        compose.onNodeWithText("阿远的便条").performClick()
        compose.onNodeWithText("我去处理货号 K17，稍后回来。").assertIsDisplayed()
        capture("small-screen-clue")
    }

    @Test fun longSettingsContentScrollsToArchiveControls() {
        val controller = TestController(play())
        controller.state.value = controller.state.value.copy(models = List(12) {
            RpgModelOption("m$it", "模型配置 $it", "服务商 · 一个较长的模型名称")
        })
        compose.setContent { Box(Modifier.requiredSize(320.dp, 480.dp)) { RpgScreen(controller, {}) } }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("导出存档").performScrollTo().assertIsDisplayed()
        capture("small-screen-settings")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = requireNotNull(context.getExternalFilesDir("rpg-ui-review"))
        folder.mkdirs()
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun play(ending: String? = null, canAct: Boolean = true) = RpgPlayState(
        "test", "雨夜旧码头", "林舟", "院门", 6, 6, "时间 0/12", "demo",
        canAct = canAct, ending = ending)

    private class TestController(play: RpgPlayState) : RpgUiController {
        override val state = MutableStateFlow(RpgUiState(campaign = play))
        var submitted: String? = null
        override fun refresh() = Unit
        override fun openCampaign(id: String) = Unit
        override fun closeCampaign() = Unit
        override fun createCampaign(title: String, playerName: String, modelId: String) = Unit
        override fun submit(text: String) { submitted = text }
        override fun confirm(turnId: String) = Unit
        override fun retry(turnId: String) = Unit
        override fun cancel(turnId: String) = Unit
        override fun stop() = Unit
        override fun useBandage() = Unit
        override fun move(locationId: String) = Unit
        override fun selectModel(modelId: String) = Unit
        override fun previewImport(uri: Uri) = Unit
        override fun confirmImport() = Unit
        override fun dismissImport() = Unit
        override fun export(uri: Uri) = Unit
        override fun dismissMessage() = Unit
    }
}
