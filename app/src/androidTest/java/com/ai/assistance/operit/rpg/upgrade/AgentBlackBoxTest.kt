package com.ai.assistance.operit.rpg.upgrade

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Black-box candidate review: Android/SQLite/JSON only; no target app classes or data seeding. */
@RunWith(AndroidJUnit4::class)
class AgentBlackBoxTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val folder by lazy { requireNotNull(context.getExternalFilesDir("upgrade-review")).apply { mkdirs() } }
    private val expectedFile get() = File(folder, "agent-ui-expected.json")

    @Test fun newAgentFlowSurvivesActivityRelaunch() {
        val title = "长团手机验收-${SystemClock.uptimeMillis()}"
        try {
            assertEquals("io.github.cgm2026.operitrpg", context.packageName)
            assertEquals("The tested candidate must be the optimized release APK", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
            launch()
            unifiedHome()
            screenshot("agent-01-unified-home")
            click("设置")
            click("模组与资料")
            scrollTo("查看并安装 · 雪线驿站失踪案")
            click("查看并安装 · 雪线驿站失踪案")
            assertFalse("Spoiler must not appear in import preview", device.hasObject(By.textContains("截信者是")))
            screenshot("agent-02-module-preview")
            click("确认安装")
            waitText("已保存。")
            click("返回")
            click("新建战役")
            fillFirstField(title)
            scrollTo("雪线驿站失踪案 · 1.0.0")
            click("雪线驿站失踪案 · 1.0.0")
            val character = device.findObject(By.desc("选择角色 · 巡行者"))
            if (character == null) scrollToContains("巡行者")
            requireNotNull(device.wait(Until.findObject(By.desc("选择角色 · 巡行者")), 15_000)).click()
            device.waitForIdle()
            scrollTo("离线流程演示 · 固定脚本，不调用云端模型")
            click("离线流程演示 · 固定脚本，不调用云端模型")
            scrollTo("开始战役")
            screenshot("agent-03-explicit-offline-model")
            click("开始战役")
            waitText(title)
            waitText("人物")
            fillFirstField("调查这里")
            click("发送")
            val campaign = awaitCampaign(title) { it.getLong("revision") == 1L && it.isNull("pendingTurnId") }
            val id = campaign.getString("id")
            val turn = latestTurn(id)
            assertEquals("COMPLETE", turn.getString("phase"))
            assertEquals("explicit-demo-v1", campaign.getString("modelFingerprint"))
            val toolIds = readDb { db -> db.rawQuery("SELECT invocationId FROM tool_result WHERE turnId=? ORDER BY ordinal", arrayOf(turn.getString("id"))).use { cursor ->
                val ids = mutableListOf<String>(); while (cursor.moveToNext()) ids += cursor.getString(0); ids
            } }
            assertEquals("A real tool loop must persist two distinct reads", 2, toolIds.size)
            assertEquals("Tool invocations must have separate persisted IDs", 2, toolIds.distinct().size)
            assertEquals(1L, campaign.getLong("lastEventSeq"))
            val recordedEvent = event(id)
            waitContains("行动已经记录")
            screenshot("agent-04-tool-loop-and-committed-result")

            click("人物")
            waitText("掌柜沈禾")
            assertFalse("Private NPC goals must not enter the player sheet", device.hasObject(By.textContains("让本地人安全过冬")))
            screenshot("agent-05-public-people")
            device.pressBack(); device.waitForIdle()

            click("规则")
            fillFirstField("行动检定")
            click("查找出处")
            val evidence = requireNotNull(device.wait(Until.findObject(By.text("行动检定 · 模组规则")), 15_000))
            evidence.click()
            waitContains("掷出1至100")
            waitText("rules.md · 行动检定")
            screenshot("agent-06-rule-source")
            device.pressBack(); device.waitForIdle()
            device.pressBack(); device.waitForIdle()

            click("记事")
            waitContains("来源事件 1")
            waitText("更正")
            screenshot("agent-07-memory-source")
            click("查看来源")
            waitText("记事来源")
            waitContains("调查这里")
            screenshot("agent-07b-memory-original-record")
            device.pressBack(); device.waitForIdle()
            click("更正")
            waitText("更正记事")
            fillFirstField("玩家补记：已在大厅进行一次调查。")
            click("确认更正")
            // The modal sheet owns accessibility while open; the message is on the background page.
            waitText("玩家补记：已在大厅进行一次调查。")
            screenshot("agent-08-corrected-memory")
            readDb { db ->
                db.rawQuery("SELECT status, COUNT(*) FROM memory_note WHERE campaignId=? GROUP BY status", arrayOf(id)).use { cursor ->
                    val statuses = mutableMapOf<String, Int>()
                    while (cursor.moveToNext()) statuses[cursor.getString(0)] = cursor.getInt(1)
                    assertEquals(1, statuses["ACTIVE"])
                    assertEquals(1, statuses["SUPERSEDED"])
                }
            }
            device.pressBack(); device.waitForIdle()
            waitText("已保存更正，原条目保留。")
            val expected = JSONObject().put("campaignId", id).put("title", title).put("event", recordedEvent)
                .put("revision", 1).put("turnId", turn.getString("id")).put("modelFingerprint", "explicit-demo-v1")
                .put("instrumentationPid", android.os.Process.myPid())
            expectedFile.writeText(expected.toString())
            launch()
            waitText(title)
            assertEquals("Activity relaunch must not reroll or append an event", recordedEvent, event(id))
            assertEquals(turn.getString("id"), latestTurn(id).getString("id"))
            screenshot("agent-09-activity-relaunch-preserved")
            File(folder, "agent-ui-result.json").writeText(JSONObject().put("passed", true).put("campaignId", id)
                .put("explicitOfflineOnly", true).put("realModelTested", false).put("readToolCount", 2)
                .put("eventCount", 1).put("ruleSourceOpened", true).put("publicNpcSheet", true)
                .put("memoryCorrectionPreservedOriginal", true).put("activityRelaunchPreserved", true)
                .put("freshProcessVerified", false).toString())
        } catch (failure: Throwable) {
            runCatching { screenshot("agent-99-failure") }
            runCatching { device.dumpWindowHierarchy(File(folder, "agent-failure-window.xml")) }
            File(folder, "agent-ui-result.json").writeText(JSONObject().put("passed", false).put("error", failure.toString()).toString())
            throw failure
        }
    }

    /** Run as a separate instrumentation invocation after force-stopping/relaunching the candidate. */
    @Test fun persistedAgentCampaignSurvivesFreshProcess() {
        assertTrue("Run the UI flow first", expectedFile.isFile)
        val expected = JSONObject(expectedFile.readText())
        assumeTrue("Run this phase in a new instrumentation process", expected.getInt("instrumentationPid") != android.os.Process.myPid())
        launch()
        waitText(expected.getString("title"))
        val id = expected.getString("campaignId")
        assertEquals(expected.getString("event"), event(id))
        assertEquals(expected.getString("turnId"), latestTurn(id).getString("id"))
        assertEquals("COMPLETE", latestTurn(id).getString("phase"))
        click("记事")
        waitText("玩家补记：已在大厅进行一次调查。")
        screenshot("agent-10-fresh-process-preserved")
        val result = JSONObject(File(folder, "agent-ui-result.json").readText())
        File(folder, "agent-ui-result.json").writeText(result.put("freshProcessVerified", true).toString())
    }

    private fun launch() {
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, "com.ai.assistance.operit.ui.main.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.waitForIdle()
    }
    private fun unifiedHome() {
        repeat(6) {
            if (device.wait(Until.hasObject(By.text("旧版内置模组开团")), 2_000) == true) return
            val all = device.findObject(By.text("全部战役"))
            if (all != null) all.click() else device.pressBack()
            device.waitForIdle()
        }
        waitText("旧版内置模组开团")
    }
    private fun fillFirstField(text: String) {
        dismissKeyboardTutorial()
        val selector = By.clazz("android.widget.EditText").pkg(context.packageName)
        val field = requireNotNull(device.wait(Until.findObject(selector), 15_000)) { "No app editable field visible" }
        field.click(); device.waitForIdle()
        dismissKeyboardTutorial()
        requireNotNull(device.wait(Until.findObject(selector), 15_000)).text = text
        device.waitForIdle()
        // Back only dismisses a positively identified IME. It must never navigate the app page.
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin")) || device.hasObject(By.pkg("com.android.inputmethod.latin"))) {
            device.pressBack(); device.waitForIdle()
        }
    }
    private fun dismissKeyboardTutorial() {
        val close = device.findObject(By.res("com.google.android.inputmethod.latin", "access_points_hint_close_button"))
        if (close != null) { close.click(); device.waitForIdle() }
    }
    private fun awaitCampaign(title: String, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            val result = readDb { db -> db.rawQuery("SELECT id,title,revision,lastEventSeq,modelFingerprint,pendingTurnId FROM campaign WHERE title=?", arrayOf(title)).use { c ->
                if (!c.moveToFirst()) null else JSONObject().put("id", c.getString(0)).put("title", c.getString(1)).put("revision", c.getLong(2)).put("lastEventSeq", c.getLong(3)).put("modelFingerprint", c.getString(4)).put("pendingTurnId", if (c.isNull(5)) JSONObject.NULL else c.getString(5))
            } }
            if (result != null && predicate(result)) return result
            SystemClock.sleep(100)
        }
        error("Explicit local action did not complete: $title")
    }
    private fun latestTurn(id: String): JSONObject = readDb { db -> db.rawQuery("SELECT json FROM turn WHERE campaignId=? ORDER BY ordinal DESC LIMIT 1", arrayOf(id)).use { c -> assertTrue(c.moveToFirst()); JSONObject(c.getString(0)) } }
    private fun event(id: String): String = readDb { db -> db.rawQuery("SELECT json FROM event WHERE campaignId=? ORDER BY seq", arrayOf(id)).use { c -> assertTrue(c.moveToFirst()); val event = c.getString(0); assertFalse("Exactly one event must be committed", c.moveToNext()); event } }
    private fun <T> readDb(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(context.getDatabasePath("rpg-agent.db").path, null, SQLiteDatabase.OPEN_READONLY).use(block)
    private fun waitText(text: String) { assertTrue("Visible text missing: $text", device.wait(Until.hasObject(By.text(text)), 15_000) == true) }
    private fun waitContains(text: String) { assertTrue("Visible text missing: $text", device.wait(Until.hasObject(By.textContains(text)), 15_000) == true) }
    private fun click(text: String) { requireNotNull(device.wait(Until.findObject(By.text(text).enabled(true)), 15_000)) { "Missing action: $text" }.click(); device.waitForIdle() }
    private fun scrollTo(text: String) {
        repeat(12) {
            if (device.wait(Until.hasObject(By.text(text)), 2_000) == true) return
            val pane = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() * it.visibleBounds.width() }
            requireNotNull(pane) { "No scroll pane while finding $text" }.scroll(Direction.DOWN, 0.72f)
            device.waitForIdle()
        }
        waitText(text)
    }
    private fun scrollToContains(text: String) {
        repeat(12) {
            if (device.hasObject(By.textContains(text)) || device.hasObject(By.descContains(text))) return
            val pane = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() * it.visibleBounds.width() }
            requireNotNull(pane) { "No scroll pane while finding $text" }.scroll(Direction.DOWN, 0.72f)
            device.waitForIdle()
        }
        waitContains(text)
    }
    private fun screenshot(name: String) { device.waitForIdle(); assertTrue(device.takeScreenshot(File(folder, "$name.png"))) }
}
