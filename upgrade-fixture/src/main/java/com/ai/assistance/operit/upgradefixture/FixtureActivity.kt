package com.ai.assistance.operit.upgradefixture

import android.app.Activity
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.widget.TextView
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Synthetic v1 data producer, never distributed as an actual historical application. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(36, 72, 36, 36); this.text = "正在准备旧版数据…" }
        setContentView(text)
        Thread {
            try {
                runBlocking { seed() }
                runOnUiThread { text.text = "READY_V1\n旧版数据库、模型偏好及预期快照已写入。" }
            } catch (error: Exception) {
                File(filesDir, "upgrade-fixture-error.txt").writeText(error.javaClass.simpleName)
                runOnUiThread { text.text = "FIXTURE_FAILED：${error.javaClass.simpleName}" }
            }
        }.start()
    }

    private suspend fun seed() {
        val ready = File(filesDir, "upgrade-fixture-ready.txt")
        if (ready.exists()) { check(ready.readText() == "READY_V1"); return }
        check(!getDatabasePath("rpg-v1.db").exists()) { "Refuse to overwrite an existing database" }
        val endpoint = "https://upgrade.invalid/v1/chat/completions"
        val modelId = "fixture-model-v1"
        val configId = "fixture-config-v1"
        val fingerprint = sha256(listOf(configId, "DEEPSEEK", modelId, endpoint, "[]", "").joinToString("\n"))
        // Gameplay is explicitly offline. The separate legacy provider preference is never invoked.
        val model = ModelConfiguration("demo", "DEMO", "script-v1", "demo-v1")
        val played = record("fixture-played-v1", "升级已玩战役", model, pending = false)
        val interrupted = record("fixture-interrupted-v1", "升级中断战役", model, pending = true)
        val serialized = listOf(played, interrupted).associate { it.id to RpgJson.instance.encodeToString(it) }
        val dbFile = getDatabasePath("rpg-v1.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS rpg_campaign (id TEXT NOT NULL, title TEXT NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, recordJson TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42, 'b231275c24184551212cba9d44162bcd')")
                listOf(played, interrupted).forEach { campaign ->
                    val values = ContentValues().apply {
                        put("id", campaign.id); put("title", campaign.title); put("updatedAt", campaign.updatedAt)
                        put("revision", campaign.state.revision); put("recordJson", serialized.getValue(campaign.id))
                    }
                    check(db.insertOrThrow("rpg_campaign", null, values) != -1L)
                }
                db.version = 1
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(getSharedPreferences("rpg-ui", 0).edit().putString("campaign", interrupted.id).commit())
        val rawConfig = buildJsonObject {
            put("id", configId); put("name", "升级旧模型配置"); put("apiKey", "fixture-fake-key-no-network")
            put("apiProviderTypeId", "DEEPSEEK"); put("apiEndpoint", endpoint); put("modelName", modelId)
            put("thinkingConfigurations", "[]"); put("thinkingOptionId", "")
            put("legacyUnknownField", "preserve-this-v1")
        }.toString()
        val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = PreferenceDataStoreFactory.create(scope = preferenceScope) { preferencesDataStoreFile("model_configs") }
            preferences.edit { values ->
                values[stringPreferencesKey("config_list")] = "[\"$configId\"]"
                values[stringPreferencesKey("config_$configId")] = rawConfig
            }
        } finally { preferenceScope.cancel() }
        val preferenceFile = preferencesDataStoreFile("model_configs")
        val expected = buildJsonObject {
            put("fixtureFormat", 1); put("selectedCampaign", interrupted.id)
            put("databaseIdentity", "b231275c24184551212cba9d44162bcd")
            put("modelConfigId", configId); put("modelFingerprint", fingerprint)
            put("modelPreferenceSha256", digest(preferenceFile.readBytes()))
            put("modelRawConfig", rawConfig)
            @Suppress("DEPRECATION")
            val signature = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures!!.single().toByteArray()
            put("signingSha256", digest(signature))
            put("records", buildJsonObject { serialized.forEach { (id, raw) -> put(id, raw) } })
        }
        File(filesDir, "upgrade-fixture-expected.json").writeText(expected.toString())
        ready.writeText("READY_V1")
    }

    private fun record(id: String, title: String, model: ModelConfiguration, pending: Boolean): CampaignRecord {
        val created = 1_700_000_000_000L // Fixed historical timestamp, safely before the upgrade run.
        val record = CampaignRecord(id, title, RainyDock.initialState("林舟"), model, created)
        val actions = listOf("move.yard_office", "inspect.office_note", "move.office_archive", "inspect.archive_ledger")
        actions.forEachIndexed { index, actionId ->
            val turnId = "$id-turn-${index + 1}"
            val action = RainyDock.actions.single { it.id == actionId }
            val plan = Plan(PlanKind.ACTION, actionId = actionId, publicIntent = action.description)
            val baseRevision = record.state.revision
            val resolved = RainyDock.resolve(record.state, actionId, turnId, object : DiceSource { override fun nextD100() = 30 })
            record.state = resolved.first
            val isPending = pending && index == actions.lastIndex
            val attemptId = "$turnId-narration-attempt"
            record.turns += Turn(turnId, id, index + 1, "$id-input-${index + 1}", action.description,
                if (isPending) TurnStage.NARRATING else TurnStage.COMPLETE, baseRevision, model, created + index,
                attemptId = if (isPending) attemptId else null, plan = plan,
                planHash = sha256(RpgJson.instance.encodeToString(plan)), actionHash = RainyDock.actionHash(actionId),
                result = resolved.second, narration = if (isPending) null else resolved.second.publicSummary,
                errorCode = if (isPending) "MODEL_REQUEST_FAILED" else null,
                attempts = if (isPending) mutableListOf(RequestAttempt(attemptId, TurnStage.NARRATING, 1, model.fingerprint, status = "FAILED", errorCode = "MODEL_REQUEST_FAILED")) else mutableListOf())
            record.events += GameEvent("$id-event-${index + 1}", id, turnId, index + 1L, "ACTION_RESOLVED", actionId, resolved.second.dice?.roll)
            record.updatedAt = created + index
        }
        check(record.state.tick == 4 && record.state.discoveredClues.size == 2 && record.turns.last().result!!.dice!!.roll == 30)
        check(RainyDock.replay(RainyDock.initialState("林舟"), record.events) == record.state)
        return record
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
