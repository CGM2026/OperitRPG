package com.ai.assistance.operit.rpg.model

import kotlinx.serialization.json.*

enum class ModelProtocol { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI }

data class ModelConfigEdit(val id: String? = null, val name: String, val apiKey: String,
    val endpoint: String, val modelNames: List<String>, val protocol: ModelProtocol) {
    override fun toString() = "ModelConfigEdit(id=$id, protocol=$protocol, credentials=redacted)"
}

data class RpgModelConfig(val id: String, val name: String, val apiKey: String,
    val endpoint: String, val modelNames: List<String>, val providerId: String,
    val protocol: ModelProtocol?, val unsupportedReason: String?, val raw: JsonObject) {
    fun snapshot(modelId: String) = ModelConfiguration(id, providerId, modelId, configurationFingerprint(this, modelId))
    override fun toString() = "RpgModelConfig(id=$id, protocol=$protocol, credentials=redacted)"
}

/** The published v0.1 fingerprint must remain byte-for-byte compatible. No secrets enter it. */
fun configurationFingerprint(config: RpgModelConfig, modelId: String): String {
    val legacy = sha256(listOf(config.id, config.providerId, modelId, config.endpoint,
        config.raw.string("thinkingConfigurations", "[]"), config.raw.string("thinkingOptionId", "")
    ).joinToString("\n"))
    // Existing protocol-compatible snapshots retain exactly the published fingerprint.
    // An explicit protocol conversion must not silently change an already pinned request.
    return if (config.raw.containsKey("d20Protocol") && config.protocol != legacyModelProtocol(config.providerId))
        sha256("$legacy\nd20Protocol=${config.raw.string("d20Protocol", "")}") else legacy
}

internal fun JsonObject.string(key: String, default: String): String {
    val value = this[key] ?: return default
    require(value is JsonPrimitive && value.isString) { "配置字段格式错误" }
    return value.content
}

/** No mutation during reads: unknown keys and unsupported providers remain in the original object. */
object RpgModelConfigCodec {
    private val json = Json { ignoreUnknownKeys = false }
    fun decode(id: String, source: String?): RpgModelConfig {
        if (source == null) return unavailable(id, "旧配置内容缺失，请重新配置", JsonObject(emptyMap()))
        val raw = try { json.parseToJsonElement(source).jsonObject } catch (_: Exception) {
            return unavailable(id, "旧配置JSON损坏，原内容保留，请重新配置", JsonObject(emptyMap()))
        }
        return try {
            require(raw.string("id", id) == id)
            val provider = raw.string("apiProviderTypeId", raw.string("apiProviderType", "DEEPSEEK"))
            val explicit = raw["d20Protocol"]?.jsonPrimitive?.content
            val protocol = if (explicit == null) legacyModelProtocol(provider) else ModelProtocol.entries.singleOrNull { it.name == explicit }
            val managed = explicit != null && protocol != null
            val advanced = !managed && (raw["useMultipleApiKeys"]?.jsonPrimitive?.booleanOrNull == true ||
                raw.string("customHeaders", "{}").trim() !in setOf("", "{}") || raw["hasCustomParameters"]?.jsonPrimitive?.booleanOrNull == true)
            val endpoint = raw.string("apiEndpoint", "")
            val models = raw.string("modelName", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val reason = when {
                protocol == null -> "此旧供应商或OAuth配置暂不支持，请明确选择协议并重新配置"
                advanced -> "此旧配置包含多密钥、自定义请求头或参数，请重新配置为单密钥文本接口"
                endpoint.isBlank() || models.isEmpty() -> "请填写接口端点与模型ID"
                runCatching { validateEndpoint(endpoint) }.isFailure -> "接口地址格式不支持，请明确重新配置"
                else -> null
            }
            RpgModelConfig(id, raw.string("name", id), raw.string("apiKey", ""), endpoint, models, provider, if (reason == null) protocol else null, reason, raw)
        } catch (_: Exception) { unavailable(id, "旧配置字段格式不支持，原内容保留，请重新配置", raw) }
    }
    fun encode(edit: ModelConfigEdit, id: String, previous: JsonObject?): String {
        require(edit.name.isNotBlank() && edit.name.length <= 100)
        require(edit.modelNames.isNotEmpty() && edit.modelNames.all { it.isNotBlank() && it.length <= 200 && ',' !in it })
        validateEndpoint(edit.endpoint)
        val legacyProvider = (previous?.get("apiProviderTypeId") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (previous?.get("apiProviderType") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val provider = legacyProvider ?: when (edit.protocol) {
            ModelProtocol.OPENAI_CHAT -> "OPENAI_GENERIC"; ModelProtocol.OPENAI_RESPONSES -> "OPENAI_RESPONSES_GENERIC"
            ModelProtocol.ANTHROPIC -> "ANTHROPIC_GENERIC"; ModelProtocol.GEMINI -> "GEMINI_GENERIC"
        }
        return JsonObject(previous.orEmpty() + mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(edit.name.trim()), "apiKey" to JsonPrimitive(edit.apiKey.trim()),
            "apiEndpoint" to JsonPrimitive(edit.endpoint.trim()), "modelName" to JsonPrimitive(edit.modelNames.joinToString(",") { it.trim() }),
            "apiProviderTypeId" to JsonPrimitive(provider), "d20Protocol" to JsonPrimitive(edit.protocol.name)
        )).toString()
    }
    private fun unavailable(id: String, reason: String, raw: JsonObject) = RpgModelConfig(id, id, "", "", emptyList(), "UNSUPPORTED", null, reason, raw)
}
internal fun legacyModelProtocol(provider: String): ModelProtocol? = when (provider.trim().uppercase()) {
        "OPENAI", "OPENAI_GENERIC", "DEEPSEEK", "XAI", "MOONSHOT", "MISTRAL", "SILICONFLOW", "OPENROUTER", "ZHIPU", "ALIYUN", "BAICHUAN", "MIMO", "NVIDIA", "LMSTUDIO", "OLLAMA", "OPENAI_LOCAL", "DOUBAO", "PPINFRA", "NOVITA" -> ModelProtocol.OPENAI_CHAT
        "OPENAI_RESPONSES", "OPENAI_RESPONSES_GENERIC" -> ModelProtocol.OPENAI_RESPONSES
        "ANTHROPIC", "ANTHROPIC_GENERIC" -> ModelProtocol.ANTHROPIC
        "GOOGLE", "GEMINI_GENERIC" -> ModelProtocol.GEMINI
        else -> null
}
