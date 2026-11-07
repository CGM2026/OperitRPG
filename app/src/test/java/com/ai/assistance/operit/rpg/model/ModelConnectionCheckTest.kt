package com.ai.assistance.operit.rpg.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ModelConnectionCheckTest {
    private class FakeTransport(var response: HttpResponse = HttpResponse(200, "{}")) : HttpTransport {
        var failure: IOException? = null
        var lastGetUrl: String? = null
        var lastGetHeaders: Map<String, String> = emptyMap()
        var lastPostUrl: String? = null
        var lastPostHeaders: Map<String, String> = emptyMap()
        var lastPostBody: String? = null

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            lastGetUrl = url; lastGetHeaders = headers; failure?.let { throw it }; return response
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            lastPostUrl = url; lastPostHeaders = headers; lastPostBody = body; failure?.let { throw it }; return response
        }
    }

    private fun config(
        protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT,
        endpoint: String = "https://api.example.com/v1"
    ): RpgModelConfig {
        val edit = ModelConfigEdit(
            id = "test", name = "test", apiKey = "secret", endpoint = endpoint,
            modelNames = listOf("test-model"), protocol = protocol
        )
        return RpgModelConfigCodec.decode("test", RpgModelConfigCodec.encode(edit, "test", null))
    }

    @Test fun listModelsParsesSortsAndDeduplicates() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"data":[{"id":"zeta"},{"id":"alpha"},{"id":"zeta"},{"id":"beta"}]}"""))
        val result = ModelConnectionCheck(fake).listModels(config())
        assertEquals(listOf("alpha", "beta", "zeta"), result)
        assertEquals("https://api.example.com/v1/models", fake.lastGetUrl)
        assertEquals("Bearer secret", fake.lastGetHeaders["Authorization"])
        assertNull(fake.lastPostUrl)
    }

    @Test fun listModelsUsesTheSameBaseRulesAsChat() = runBlocking {
        for ((endpoint, expected) in listOf(
            "https://api.example.com" to "https://api.example.com/v1/models",
            "https://api.example.com/v1/" to "https://api.example.com/v1/models",
            "https://api.example.com/custom/v1" to "https://api.example.com/custom/v1/models"
        )) {
            val fake = FakeTransport(HttpResponse(200, """{"data":[]}"""))
            ModelConnectionCheck(fake).listModels(config(endpoint = endpoint))
            assertEquals(expected, fake.lastGetUrl)
        }
    }

    @Test fun listModelsRejectsNonOpenAIProtocolsWithoutNetwork() = runBlocking {
        for (protocol in listOf(ModelProtocol.ANTHROPIC, ModelProtocol.GEMINI)) {
            val fake = FakeTransport()
            val error = expectError { ModelConnectionCheck(fake).listModels(config(protocol)) }
            assertTrue(error is ModelListUnsupported)
            assertTrue(error.message!!.contains("该协议不支持列出模型"))
            assertNull(fake.lastGetUrl)
        }
        val fake = FakeTransport()
        val unavailable = config().copy(protocol = null, unsupportedReason = "请重新配置")
        val error = expectError { ModelConnectionCheck(fake).listModels(unavailable) }
        assertTrue(error is ModelListUnsupported)
        assertNull(fake.lastGetUrl)
    }

    @Test fun httpErrorsMapToReadableMessagesWithCodes() = runBlocking {
        for ((code, expected) in listOf(
            401 to "密钥无效（401）",
            403 to "密钥无效（403）",
            404 to "地址不对（404）",
            429 to "额度或频率受限（429）",
            500 to "服务端错误（500）",
            503 to "服务端错误（503）",
            400 to "请求失败（400）"
        )) {
            val error = expectError { ModelConnectionCheck(FakeTransport(HttpResponse(code, ""))).listModels(config()) }
            assertTrue(error is ModelConnectionError)
            assertEquals(expected, error.message)
            assertEquals(code, (error as ModelConnectionError).httpCode)
        }
    }

    @Test fun timeoutAndNetworkFailuresMapToReadableMessages() = runBlocking {
        val timeout = expectError {
            ModelConnectionCheck(FakeTransport().also { it.failure = SocketTimeoutException("slow") }).listModels(config())
        }
        assertTrue(timeout is ModelConnectionError.Timeout)
        assertEquals("连接超时", timeout.message)
        assertNull((timeout as ModelConnectionError).httpCode)

        val unreachable = expectError {
            ModelConnectionCheck(FakeTransport().also { it.failure = UnknownHostException("no host") }).listModels(config())
        }
        assertTrue(unreachable is ModelConnectionError.Unreachable)
        assertEquals("无法连接服务器", unreachable.message)
        assertNull((unreachable as ModelConnectionError).httpCode)
    }

    @Test fun malformedModelListMapsToReadableMessage() = runBlocking {
        for (body in listOf("not json", "{}", """{"data":{}}""", """{"data":[{"name":"no-id"}]}""")) {
            val error = expectError { ModelConnectionCheck(FakeTransport(HttpResponse(200, body))).listModels(config()) }
            assertTrue(error is ModelConnectionError.MalformedReply)
            assertEquals("返回格式不是 OpenAI 兼容（200）", error.message)
            assertEquals(200, (error as ModelConnectionError).httpCode)
        }
    }

    @Test fun verifySendsPingWithEightTokenBudget() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"choices":[]}"""))
        ModelConnectionCheck(fake).verify(config(), "test-model")
        assertEquals("https://api.example.com/v1/chat/completions", fake.lastPostUrl)
        assertEquals("Bearer secret", fake.lastPostHeaders["Authorization"])
        val body = Json.parseToJsonElement(fake.lastPostBody!!).jsonObject
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals(8, body.getValue("max_tokens").jsonPrimitive.int)
        val messages = body.getValue("messages").jsonArray
        val user = messages.last().jsonObject
        assertEquals("user", user.getValue("role").jsonPrimitive.content)
        assertEquals("ping", user.getValue("content").jsonPrimitive.content)
    }

    @Test fun verifyUsesProtocolSpecificEndpointAndHeaders() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, "{}"))
        ModelConnectionCheck(fake).verify(config(ModelProtocol.ANTHROPIC), "claude")
        assertEquals("https://api.example.com/v1/messages", fake.lastPostUrl)
        assertEquals("secret", fake.lastPostHeaders["x-api-key"])
        assertEquals("2023-06-01", fake.lastPostHeaders["anthropic-version"])
        assertNull(fake.lastPostHeaders["Authorization"])
    }

    @Test fun verifyMapsHttpErrorsAndMissingConfiguration() = runBlocking {
        val unauthorized = expectError {
            ModelConnectionCheck(FakeTransport(HttpResponse(401, ""))).verify(config(), "test-model")
        }
        assertTrue(unauthorized is ModelConnectionError.InvalidKey)
        assertEquals("密钥无效（401）", unauthorized.message)

        val missing = config().copy(protocol = null, unsupportedReason = "请重新配置")
        val unavailable = expectError { ModelConnectionCheck(FakeTransport()).verify(missing, "test-model") }
        assertTrue(unavailable is ModelConnectionError.InvalidConfiguration)
        assertEquals("请重新配置", unavailable.message)
    }

    private suspend fun expectError(block: suspend () -> Unit): Exception {
        try {
            block()
        } catch (e: Exception) {
            return e
        }
        throw AssertionError("Expected explicit failure")
    }
}
