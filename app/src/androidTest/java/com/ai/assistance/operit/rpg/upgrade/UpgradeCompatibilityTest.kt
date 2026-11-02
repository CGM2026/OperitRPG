package com.ai.assistance.operit.rpg.upgrade

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Black-box release upgrade: no target Room/core/model classes and no instrumentation reseeding. */
@RunWith(AndroidJUnit4::class)
class UpgradeCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val folder by lazy { requireNotNull(context.getExternalFilesDir("upgrade-review")).apply { mkdirs() } }
    private val playedId = "fixture-played-v1"
    private val interruptedId = "fixture-interrupted-v1"

    @Test fun signedVersionOneDataSurvivesDedicatedReleaseUpgrade() {
        try {
            assertEquals("io.github.cgm2026.operitrpg", context.packageName)
            val marker = File(context.filesDir, "upgrade-fixture-ready.txt")
            assertTrue("Install and launch the synthetic v1 fixture before replacing it with the release APK", marker.isFile)
            assertEquals("READY_V1", marker.readText())
            val expectedText = File(context.filesDir, "upgrade-fixture-expected.json").readText()
            val expected = JSONObject(expectedText)
            File(folder, "fixture-expected.json").writeText(expectedText)
            File(folder, "fixture-ready.txt").writeText(marker.readText())
            assertEquals(1, expected.getInt("fixtureFormat"))
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            assertEquals(3L, versionCode)
            assertEquals("二十面骰", context.applicationInfo.loadLabel(context.packageManager).toString())
            assertEquals("This must test the optimized release APK", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
            @Suppress("DEPRECATION")
            val signature = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.single().toByteArray()
            assertEquals(expected.getString("signingSha256"), digest(signature))
            val preferencesFile = File(context.filesDir, "datastore/model_configs.preferences_pb")
            assertEquals(expected.getString("modelPreferenceSha256"), digest(preferencesFile.readBytes()))
            assertEquals(expected.getString("selectedCampaign"), context.getSharedPreferences("rpg-ui", 0).getString("campaign", null))
            assertEquals(interruptedId, expected.getString("selectedCampaign"))
            readDatabase { db ->
                assertEquals(1, db.version)
                db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(expected.getString("databaseIdentity"), it.getString(0))
                }
            }
            val expectedRecords = expected.getJSONObject("records")
            val rawBefore = readRecords()
            assertEquals(setOf(playedId, interruptedId), rawBefore.keys)
            rawBefore.forEach { (id, raw) -> assertEquals("Upgrade must preserve bytes before the app opens", expectedRecords.getString(id), raw) }
            val playedBefore = JSONObject(rawBefore.getValue(playedId))
            val pendingBefore = JSONObject(rawBefore.getValue(interruptedId))
            assertSeed(playedBefore); assertSeed(pendingBefore)
            assertEquals("NARRATING", pendingBefore.getJSONArray("turns").getJSONObject(3).getString("stage"))
            assertTrue(pendingBefore.getJSONArray("turns").getJSONObject(3).isNull("narration"))

            context.startActivity(Intent().setComponent(ComponentName(context.packageName, "com.ai.assistance.operit.ui.main.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitText("升级中断战役")
            scrollToText("继续叙述")
            screenshot("01-release-open-pending")
            val opened = JSONObject(readRecords().getValue(interruptedId))
            assertMechanicsSame(pendingBefore, opened)
            assertEquals("Opening the app must not start a model request", 1, opened.getJSONArray("turns").getJSONObject(3).getJSONArray("attempts").length())

            click("继续叙述")
            val resumed = awaitRecord(interruptedId) { it.getJSONArray("turns").getJSONObject(3).getString("stage") == "COMPLETE" }
            assertMechanicsSame(pendingBefore, resumed)
            val resumedTurn = resumed.getJSONArray("turns").getJSONObject(3)
            assertFalse(resumedTurn.getString("narration").isBlank())
            assertEquals("DEMO", resumedTurn.getJSONObject("model").getString("providerId"))
            assertEquals("Exactly one explicit narration retry", 2, resumedTurn.getJSONArray("attempts").length())
            assertTrue(device.wait(Until.gone(By.text("继续叙述")), 15_000) == true)
            screenshot("02-pending-resumed-without-reroll")

            click("返回")
            waitText("二十面骰")
            click("升级已玩战役")
            waitText("升级已玩战役")
            click("地点")
            scrollToText("前往办公室", preferLastScrollable = true)
            click("前往办公室")
            val continued = awaitRecord(playedId) { it.getJSONObject("state").getLong("revision") == 5L && it.getJSONArray("turns").getJSONObject(4).getString("stage") == "COMPLETE" }
            assertEquals(5, continued.getJSONObject("state").getInt("tick"))
            assertEquals("office", continued.getJSONObject("state").getString("locationId"))
            assertEquals(5, continued.getJSONArray("events").length())
            repeat(4) { index ->
                assertEquals(playedBefore.getJSONArray("turns").getJSONObject(index).toString(), continued.getJSONArray("turns").getJSONObject(index).toString())
                assertEquals(playedBefore.getJSONArray("events").getJSONObject(index).toString(), continued.getJSONArray("events").getJSONObject(index).toString())
            }
            assertEquals(playedBefore.getJSONObject("state").getJSONArray("discoveredClues").toString(), continued.getJSONObject("state").getJSONArray("discoveredClues").toString())
            assertEquals("DEMO", continued.getJSONArray("turns").getJSONObject(4).getJSONObject("model").getString("providerId"))
            assertTrue(device.wait(Until.hasObject(By.textContains("时间 5/12")), 15_000) == true)
            screenshot("03-played-campaign-continued")

            click("设置")
            scrollToText("管理模型配置")
            click("管理模型配置")
            scrollToText("升级旧模型配置")
            scrollToText("https://upgrade.invalid/v1/chat/completions")
            assertFalse("The key must remain masked in the visible settings", device.hasObject(By.text("fixture-fake-key-no-network")))
            assertEquals("Viewing legacy settings must not rewrite their preference bytes", expected.getString("modelPreferenceSha256"), digest(preferencesFile.readBytes()))
            val rawConfig = JSONObject(expected.getString("modelRawConfig"))
            val legacyFingerprint = digest(listOf(rawConfig.getString("id"), rawConfig.getString("apiProviderTypeId"), rawConfig.getString("modelName"),
                rawConfig.getString("apiEndpoint"), rawConfig.getString("thinkingConfigurations"), rawConfig.getString("thinkingOptionId")).joinToString("\n").toByteArray(Charsets.UTF_8))
            assertEquals(expected.getString("modelFingerprint"), legacyFingerprint)
            screenshot("04-legacy-model-visible-key-masked")
            File(folder, "observed-after.json").writeText(JSONObject(readRecords()).toString())
            File(folder, "upgrade-result.json").writeText(JSONObject().put("passed", true).put("versionCode", 2)
                .put("selectedCampaignBeforeLaunch", interruptedId).put("oldPreferenceBytesUnchanged", true)
                .put("pendingRoll", 30).put("pendingEventsBeforeAndAfter", 4).put("continuedTick", 5)
                .put("gameplayProvider", "DEMO").put("fixtureIsHistoricalApk", false).toString())
        } catch (failure: Throwable) {
            runCatching { screenshot("99-failure") }
            runCatching { File(folder, "observed-failure.json").writeText(JSONObject(readRecords()).toString()) }
            throw failure
        }
    }

    private fun assertSeed(record: JSONObject) {
        assertEquals(4, record.getJSONObject("state").getInt("tick"))
        assertEquals(4, record.getJSONArray("turns").length())
        assertEquals(4, record.getJSONArray("events").length())
        assertEquals(2, record.getJSONObject("state").getJSONArray("discoveredClues").length())
        assertEquals(30, record.getJSONArray("turns").getJSONObject(3).getJSONObject("result").getJSONObject("dice").getInt("roll"))
        assertEquals("DEMO", record.getJSONObject("model").getString("providerId"))
    }
    private fun assertMechanicsSame(before: JSONObject, after: JSONObject) {
        assertEquals(before.getJSONObject("state").toString(), after.getJSONObject("state").toString())
        assertEquals(before.getJSONArray("events").toString(), after.getJSONArray("events").toString())
        assertEquals(before.getJSONArray("turns").getJSONObject(3).getJSONObject("result").toString(), after.getJSONArray("turns").getJSONObject(3).getJSONObject("result").toString())
        assertEquals(before.getJSONArray("turns").getJSONObject(3).getString("id"), after.getJSONArray("turns").getJSONObject(3).getString("id"))
    }
    private fun readRecords(): Map<String, String> = readDatabase { db ->
        val records = linkedMapOf<String, String>()
        db.rawQuery("SELECT id,recordJson FROM rpg_campaign ORDER BY id", null).use { cursor -> while (cursor.moveToNext()) records[cursor.getString(0)] = cursor.getString(1) }
        records
    }
    private fun <T> readDatabase(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(context.getDatabasePath("rpg-v1.db").path, null, SQLiteDatabase.OPEN_READONLY).use(block)
    private fun awaitRecord(id: String, condition: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val record = JSONObject(readRecords().getValue(id))
            if (condition(record)) return record
            SystemClock.sleep(100)
        }
        error("Timed out waiting for explicitly requested local gameplay: $id")
    }
    private fun waitText(text: String) { assertTrue("Visible text missing: $text", device.wait(Until.hasObject(By.text(text)), 15_000) == true) }
    private fun scrollToText(text: String, preferLastScrollable: Boolean = false) {
        repeat(10) {
            if (device.hasObject(By.text(text))) return
            // LazyColumn exposes only visible items. Scroll the actual pane rather than assuming
            // the fourth saved turn or a settings control is already in the accessibility tree.
            val panes = device.findObjects(By.scrollable(true)).filter { it.visibleBounds.height() > 0 }
            val pane = if (preferLastScrollable) panes.lastOrNull() else panes.maxByOrNull { it.visibleBounds.height() * it.visibleBounds.width() }
            requireNotNull(pane) { "No visible scrollable pane while looking for: $text" }
            pane.scroll(Direction.DOWN, 0.75f)
            device.waitForIdle()
        }
        waitText(text)
    }
    private fun click(text: String) { requireNotNull(device.wait(Until.findObject(By.text(text).enabled(true)), 15_000)) { "Missing enabled action: $text" }.click(); device.waitForIdle() }
    private fun screenshot(name: String) { device.waitForIdle(); assertTrue(device.takeScreenshot(File(folder, "$name.png"))) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
