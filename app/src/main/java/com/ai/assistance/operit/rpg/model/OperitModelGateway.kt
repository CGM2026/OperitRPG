package com.ai.assistance.operit.rpg.model

import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModelRequestException(message: String) : IOException(message)

/** No retries, redirects, tools, shared chat state, provider framework or request/body logging. */
class OperitModelGateway(
    private val configuration: suspend (String) -> RpgModelConfig?,
    client: OkHttpClient = OkHttpClient(),
    private val timeoutMillis: Long = 60_000
) : ModelGateway {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    private val active = ConcurrentHashMap<String, Call>()
    override suspend fun plan(request: ModelRequest) = run(request)
    override suspend fun narrate(request: ModelRequest) = run(request)
    override suspend fun cancel(attemptId: String) { active[attemptId]?.cancel() }
    fun cancelNow(attemptId: String) { active[attemptId]?.cancel() }
    /** Long-form context is assembled by its own engine and must never be reshaped as legacy history. */
    suspend fun generateText(request: ModelRequest): ModelReply = run(request, legacyBudget = false)
    private suspend fun run(request: ModelRequest, legacyBudget: Boolean = true): ModelReply = withContext(Dispatchers.IO) {
        val config = configuration(request.configuration.configurationId) ?: throw ModelRequestException("模型配置已删除，请恢复原配置或明确重新绑定中断叙述")
        val protocol = config.protocol ?: throw ModelRequestException(config.unsupportedReason ?: "此配置需要重新配置")
        require(config.providerId == request.configuration.providerId && request.configuration.modelId in config.modelNames &&
            configurationFingerprint(config, request.configuration.modelId) == request.configuration.fingerprint) { "模型配置已改变，请恢复原配置或明确重新绑定中断叙述" }
        require(config.apiKey.length <= 16_384 && config.apiKey.all { it.code in 0x20..0x7e }) { "API Key包含无效字符，请重新填写" }
        val prompt = if (legacyBudget) ModelProtocolCodec.budget(request.systemPrompt, request.userPrompt) else {
            require(request.maxOutputTokens in 1..8192 && request.systemPrompt.length + request.userPrompt.length <= 64_000) { "模型输入超过预算" }
            val estimate = (request.systemPrompt + request.userPrompt).sumOf { if (it.code in 0x4E00..0x9FFF) 1.5 else 0.5 }
            require(estimate <= 12_000) { "必要上下文超过输入预算，请缩小任务范围" }
            request.userPrompt
        }
        val body = ModelProtocolCodec.request(protocol, request.configuration.modelId, request.systemPrompt, prompt, request.maxOutputTokens)
        val builder = Request.Builder().url(modelEndpoint(config, request.configuration.modelId))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (protocol == ModelProtocol.ANTHROPIC) builder.header("anthropic-version", "2023-06-01")
        if (config.apiKey.isNotBlank()) when (protocol) {
            ModelProtocol.OPENAI_CHAT, ModelProtocol.OPENAI_RESPONSES -> builder.header("Authorization", "Bearer ${config.apiKey}")
            ModelProtocol.ANTHROPIC -> builder.header("x-api-key", config.apiKey)
            ModelProtocol.GEMINI -> builder.header("x-goog-api-key", config.apiKey)
        }
        val call = http.newCall(builder.build())
        check(active.putIfAbsent(request.attemptId, call) == null) { "请求ID已在进行中" }
        try { withTimeout(timeoutMillis) { awaitReply(call, protocol, config.apiKey) } }
        finally { active.remove(request.attemptId, call); call.cancel() }
    }
    private suspend fun awaitReply(call: Call, protocol: ModelProtocol, apiKey: String): ModelReply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ModelRequestException(if (call.isCanceled()) "请求已取消或超时" else "网络请求未完成，请检查连接后手动重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        if (it.code in 300..399) throw ModelRequestException("接口返回重定向，已阻止发送密钥；请直接填写最终接口地址")
                        if (!it.isSuccessful) throw ModelRequestException("模型接口返回HTTP ${it.code}，请检查配置后手动重试")
                        val body = it.body ?: throw ModelRequestException("模型返回空响应")
                        if (body.contentLength() > MAX_RESPONSE_BYTES) throw ModelRequestException("模型响应超过大小上限")
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count == -1) break
                                if (bytes.size() + count > MAX_RESPONSE_BYTES) throw ModelRequestException("模型响应超过大小上限")
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                        ModelProtocolCodec.response(protocol, text).also { reply ->
                            if (apiKey.isNotEmpty() && reply.text.contains(apiKey)) throw ModelRequestException("接口正文包含凭据，已拒绝保存")
                        }
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is ModelRequestException) e else ModelRequestException("模型响应格式不符合所选协议"))
                }
            }
        })
    }
    companion object { const val MAX_RESPONSE_BYTES = 1_048_576 }
}

fun validateEndpoint(endpoint: String): HttpUrl {
    val text = endpoint.trim().removeSuffix("#")
    require(text.none { it == '\\' || it.isISOControl() }) { "接口地址包含无效字符" }
    val url = text.toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的HTTP或HTTPS接口地址")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "接口地址不得含用户名、密码、查询密钥或片段" }
    return url
}

/** Retains published completion rules for legacy endpoints; never changes host or protocol. */
fun modelEndpoint(config: RpgModelConfig, model: String): HttpUrl {
    val base = validateEndpoint(config.endpoint)
    val protocol = requireNotNull(config.protocol)
    if (protocol == ModelProtocol.GEMINI) {
        val id = model.removePrefix("models/"); require('/' !in id && id.isNotBlank()) { "Gemini模型ID格式不正确" }
        val path = base.encodedPath.trimEnd('/')
        val prefix = if ("d20Protocol" !in config.raw) "/v1beta" else when {
            "/models/" in path -> path.substringBefore("/models/")
            path.endsWith("/v1beta") || path.endsWith("/v1") -> path
            path.isEmpty() -> "/v1beta"
            else -> "$path/v1beta"
        }
        return base.newBuilder().encodedPath(prefix).addPathSegment("models").addPathSegment("$id:generateContent").build()
    }
    if (config.endpoint.trim().endsWith('#')) return base
    val path = base.encodedPath.trimEnd('/')
    val suffix = when (protocol) { ModelProtocol.OPENAI_CHAT -> "chat/completions"; ModelProtocol.OPENAI_RESPONSES -> "responses"; ModelProtocol.ANTHROPIC -> "messages"; else -> error("Invalid protocol") }
    val completed = when {
        path.isEmpty() -> "/v1/$suffix"
        path.endsWith("/v1", true) -> "$path/$suffix"
        protocol == ModelProtocol.ANTHROPIC && path.endsWith("/anthropic", true) -> "$path/v1/messages"
        else -> path
    }
    return base.newBuilder().encodedPath(completed).build()
}

/** DEMO requires an explicit user selection and is never selected after a network error. */
class SelectedModelGateway(private val real: ModelGateway) : ModelGateway {
    private val demo = FakeModelGateway()
    private val demoConfiguration = ModelConfiguration("demo", "DEMO", "script-v1", "demo-v1")
    override suspend fun plan(request: ModelRequest) = if (request.configuration == demoConfiguration) demo.plan(request) else real.plan(request)
    override suspend fun narrate(request: ModelRequest) = if (request.configuration == demoConfiguration) demo.narrate(request) else real.narrate(request)
    override suspend fun cancel(attemptId: String) = real.cancel(attemptId)
}
