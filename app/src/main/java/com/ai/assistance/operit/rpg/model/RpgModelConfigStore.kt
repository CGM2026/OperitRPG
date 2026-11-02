package com.ai.assistance.operit.rpg.model

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

private val Context.d20ModelPreferences by preferencesDataStore(name = "model_configs")

/** Uses the existing published preference file without migrations, deletion or default insertion. */
class RpgModelConfigStore private constructor(context: Context) {
    private val data = context.applicationContext.d20ModelPreferences
    private val idsKey = stringPreferencesKey("config_list")
    val configurations: Flow<List<RpgModelConfig>> = data.data.map { preferences ->
        ids(preferences).map { id -> RpgModelConfigCodec.decode(id, preferences[stringPreferencesKey("config_$id")]) }
    }
    suspend fun list(): List<RpgModelConfig> = configurations.first()
    suspend fun get(id: String): RpgModelConfig? {
        val source = data.data.first()[stringPreferencesKey("config_$id")] ?: return null
        return RpgModelConfigCodec.decode(id, source)
    }
    suspend fun save(edit: ModelConfigEdit): String {
        val id = edit.id ?: UUID.randomUUID().toString()
        require(id.isNotBlank())
        data.edit { preferences ->
            val key = stringPreferencesKey("config_$id")
            val old = preferences[key]?.let { RpgModelConfigCodec.decode(id, it).raw }
            preferences[key] = RpgModelConfigCodec.encode(edit, id, old)
            val ids = ids(preferences)
            if (id !in ids) preferences[idsKey] = JsonArray((ids + id).map(::JsonPrimitive)).toString()
        }
        return id
    }
    suspend fun delete(id: String) {
        data.edit { preferences ->
            val ids = ids(preferences)
            preferences.remove(stringPreferencesKey("config_$id"))
            preferences[idsKey] = JsonArray(ids.filter { it != id }.map(::JsonPrimitive)).toString()
        }
    }
    private fun ids(preferences: Preferences): List<String> {
        val source = preferences[idsKey] ?: return emptyList()
        if (source.isEmpty()) return emptyList()
        val values = Json.parseToJsonElement(source).jsonArray.map { element ->
            require(element.jsonPrimitive.isString); element.jsonPrimitive.content.also { require(it.isNotBlank()) }
        }
        require(values.distinct().size == values.size) { "旧模型配置列表包含重复ID，请保留原文件后检查" }
        return values
    }
    companion object {
        @Volatile private var instance: RpgModelConfigStore? = null
        fun get(context: Context): RpgModelConfigStore = instance ?: synchronized(this) {
            instance ?: RpgModelConfigStore(context).also { instance = it }
        }
    }
}
