package com.ai.assistance.operit.rpg.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** User-facing connection failures. Every message is a short Chinese sentence carrying the HTTP code when one exists. */
sealed class ModelConnectionError(message: String, val httpCode: Int? = null) : Exception(message) {
    class InvalidKey(code: Int) : ModelConnectionError("密钥无效（$code）", code)
    class EndpointNotFound : ModelConnectionError("地址不对（404）", 404)
    class RateLimited : ModelConnectionError("额度或频率受限（429）", 429)
    class ServerFailure(code: Int) : ModelConnectionError("服务端错误（$code）", code)
    class Timeout : ModelConnectionError("连接超时", null)
    class MalformedReply(code: Int? = null) :
        ModelConnectionError(if (code == null) "返回格式不是 OpenAI 兼容" else "返回格式不是 OpenAI 兼容（$code）", code)
    class Unreachable : ModelConnectionError("无法连接服务器", null)
    class HttpFailure(code: Int) : ModelConnectionError("请求失败（$code）", code)
    class InvalidConfiguration(message: String) : ModelConnectionError(message, null)
}

class ModelListUnsupported(message: String = "该协议不支持列出模型，请手动填写") : Exception(message)

data class HttpResponse(val code: Int, val body: String)

/** Minimal HTTP seam so connection checks can be tested without a socket. */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

class OkHttpTransport(
    client: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = 30_000
) : HttpTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = execute(
        Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.get().build()
    )

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = execute(
        Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toRequestBody(JSON)).build()
    )

    private suspend fun execute(request: Request): HttpResponse = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            HttpResponse(response.code, response.body?.string().orEmpty())
        }
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}

class ModelConnectionCheck(private val transport: HttpTransport = OkHttpTransport()) {
    /** Lists OpenAI-compatible model ids. Non-OpenAI protocols fail loudly instead of returning an empty list. */
    suspend fun listModels(config: RpgModelConfig): List<String> {
        val protocol = config.protocol
        if (protocol != ModelProtocol.OPENAI_CHAT && protocol != ModelProtocol.OPENAI_RESPONSES) {
            throw ModelListUnsupported()
        }
        validateApiKey(config)
        val url = endpoint { modelListEndpoint(config) }
        val response = request { transport.get(url.toString(), headersFor(config, protocol)) }
        ensureSuccess(response)
        val models = try {
            Json.parseToJsonElement(response.body).jsonObject.getValue("data").jsonArray.map { item ->
                val id = item.jsonObject.getValue("id").jsonPrimitive
                require(id.isString) { "模型ID不是字符串" }
                id.content
            }.filter { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            throw ModelConnectionError.MalformedReply(response.code)
        }
        return models.distinct().sorted()
    }

    /** Sends one "ping" user message with an 8-token budget through the same endpoint/body rules as chat generation. */
    suspend fun verify(config: RpgModelConfig, model: String) {
        val protocol = config.protocol
            ?: throw ModelConnectionError.InvalidConfiguration(config.unsupportedReason ?: "模型配置不可用，请重新配置")
        validateApiKey(config)
        val url = endpoint { modelEndpoint(config, model) }
        val body = ModelProtocolCodec.request(protocol, model, "", "ping", 8).toString()
        val response = request { transport.post(url.toString(), headersFor(config, protocol), body) }
        ensureSuccess(response)
    }

    private fun modelListEndpoint(config: RpgModelConfig): HttpUrl {
        val completed = modelEndpoint(config, "model")
        val path = completed.encodedPath.trimEnd('/')
        val base = when {
            path.endsWith("/chat/completions") -> path.dropLast("/chat/completions".length)
            path.endsWith("/responses") -> path.dropLast("/responses".length)
            else -> path
        }
        return completed.newBuilder().encodedPath("$base/models").build()
    }

    private fun headersFor(config: RpgModelConfig, protocol: ModelProtocol): Map<String, String> = buildMap {
        if (protocol == ModelProtocol.ANTHROPIC) put("anthropic-version", "2023-06-01")
        if (config.apiKey.isNotBlank()) when (protocol) {
            ModelProtocol.OPENAI_CHAT, ModelProtocol.OPENAI_RESPONSES -> put("Authorization", "Bearer ${config.apiKey}")
            ModelProtocol.ANTHROPIC -> put("x-api-key", config.apiKey)
            ModelProtocol.GEMINI -> put("x-goog-api-key", config.apiKey)
        }
    }

    private fun validateApiKey(config: RpgModelConfig) {
        if (config.apiKey.isNotEmpty() &&
            (config.apiKey.length > 16_384 || config.apiKey.any { it.code !in 0x20..0x7e })
        ) {
            throw ModelConnectionError.InvalidConfiguration("API Key包含无效字符，请重新填写")
        }
    }

    private inline fun endpoint(block: () -> HttpUrl): HttpUrl = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ModelConnectionError.InvalidConfiguration(e.message ?: "接口地址格式不支持")
    }

    private suspend fun request(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ModelConnectionError) {
        throw e
    } catch (e: IOException) {
        throw when (e) {
            is SocketTimeoutException, is InterruptedIOException -> ModelConnectionError.Timeout()
            else -> ModelConnectionError.Unreachable()
        }
    } catch (_: Exception) {
        throw ModelConnectionError.MalformedReply()
    }

    private fun ensureSuccess(response: HttpResponse) {
        val code = response.code
        when {
            code in 200..299 -> Unit
            code == 401 || code == 403 -> throw ModelConnectionError.InvalidKey(code)
            code == 404 -> throw ModelConnectionError.EndpointNotFound()
            code == 429 -> throw ModelConnectionError.RateLimited()
            code in 500..599 -> throw ModelConnectionError.ServerFailure(code)
            else -> throw ModelConnectionError.HttpFailure(code)
        }
    }
}
