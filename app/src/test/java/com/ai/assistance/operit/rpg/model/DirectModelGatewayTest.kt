package com.ai.assistance.operit.rpg.model

import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DirectModelGatewayTest {
    private lateinit var server: MockWebServer
    private val clients = mutableListOf<OkHttpClient>()
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown(); clients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() } }
    private fun config(protocol: ModelProtocol, endpoint: String = server.url("/v1").toString()): RpgModelConfig {
        val edit = ModelConfigEdit(name="test",apiKey="test-secret",endpoint=endpoint,modelNames=listOf("test-model"),protocol=protocol)
        return RpgModelConfigCodec.decode("test",RpgModelConfigCodec.encode(edit,"test",null))
    }
    private fun gateway(config: RpgModelConfig, timeout:Long=60_000): OperitModelGateway {
        val client=OkHttpClient(); clients+=client
        return OperitModelGateway({ id -> if(id==config.id) config else null },client,timeout)
    }
    private fun request(config:RpgModelConfig,id:String="attempt")=ModelRequest(id,config.snapshot("test-model"),"system-rules","{\"input\":\"玩家行动\",\"history\":[]}",800)
    private fun response(protocol:ModelProtocol):String=when(protocol) {
        ModelProtocol.OPENAI_CHAT -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","reasoning_content":"PRIVATE","content":"可见正文"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        ModelProtocol.OPENAI_RESPONSES -> """{"status":"completed","error":null,"output":[{"type":"reasoning","summary":[{"text":"PRIVATE"}],"encrypted_content":"PRIVATE"},{"type":"message","content":[{"type":"output_text","text":"可见正文"}]}],"usage":{"input_tokens":10,"output_tokens":5}}"""
        ModelProtocol.ANTHROPIC -> """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"PRIVATE"},{"type":"text","text":"可见正文"}],"usage":{"input_tokens":10,"output_tokens":5}}"""
        ModelProtocol.GEMINI -> """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"PRIVATE"},{"text":"可见正文","thoughtSignature":"PRIVATE"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
    }
    @Test fun allFourProtocolsSendOnlyExplicitTextAndExtractVisibleResponse()=runBlocking {
        for(protocol in ModelProtocol.entries) {
            server.enqueue(MockResponse().setBody(response(protocol)).setHeader("Content-Type","application/json"))
            val config=config(protocol); val result=gateway(config).plan(request(config,protocol.name))
            assertEquals("可见正文",result.text); assertEquals(Usage(10,5),result.usage)
            val sent=server.takeRequest(2,TimeUnit.SECONDS)!!
            val body=Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertFalse(body.containsKey("tools")); assertFalse(body.containsKey("tool_choice")); assertFalse(body.toString().contains("PRIVATE"))
            when(protocol) {
                ModelProtocol.OPENAI_CHAT -> { assertEquals("/v1/chat/completions",sent.path); assertEquals("Bearer test-secret",sent.getHeader("Authorization")); assertEquals(800,body.getValue("max_tokens").jsonPrimitive.int); assertFalse(body.getValue("stream").jsonPrimitive.boolean) }
                ModelProtocol.OPENAI_RESPONSES -> { assertEquals("/v1/responses",sent.path); assertFalse(body.getValue("store").jsonPrimitive.boolean); assertEquals(800,body.getValue("max_output_tokens").jsonPrimitive.int) }
                ModelProtocol.ANTHROPIC -> { assertEquals("/v1/messages",sent.path); assertEquals("test-secret",sent.getHeader("x-api-key")); assertEquals("2023-06-01",sent.getHeader("anthropic-version")); assertEquals("system-rules",body.getValue("system").jsonPrimitive.content) }
                ModelProtocol.GEMINI -> { assertEquals("/v1/models/test-model:generateContent",sent.path); assertEquals("test-secret",sent.getHeader("x-goog-api-key")); assertFalse(sent.path!!.contains("test-secret")); assertTrue(body.containsKey("systemInstruction")) }
            }
        }
        assertEquals(4,server.requestCount)
    }
    @Test fun redirectNeverSendsKeyToOtherHostOrMakesSecondRequest()=runBlocking {
        val target=MockWebServer(); target.start()
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",target.url("/stolen")))
            val c=config(ModelProtocol.OPENAI_CHAT); val error=expectFailure { gateway(c).plan(request(c)) }
            assertTrue(error.message!!.contains("重定向")); assertEquals(1,server.requestCount); assertEquals(0,target.requestCount)
        } finally { target.shutdown() }
    }
    @Test fun httpFailureIsOneRequestWithSafeErrorAndNoDemo()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("test-secret PRIVATE upstream body"))
        val c=config(ModelProtocol.OPENAI_CHAT); val selected=SelectedModelGateway(gateway(c))
        val error=expectFailure { selected.plan(request(c)) }
        assertTrue(error.message!!.contains("401")); assertFalse(error.toString().contains("test-secret")); assertFalse(error.toString().contains("PRIVATE")); assertEquals(1,server.requestCount)
    }
    @Test fun malformedEmptyTruncatedAndToolResponsesAreRejectedWithoutRetry()=runBlocking {
        val c=config(ModelProtocol.OPENAI_CHAT); val g=gateway(c)
        val bad=listOf("not JSON","{}","""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}""",
            """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":"text","tool_calls":[{"function":{"name":"shell"}}]}}]}""")
        bad.forEachIndexed { index,text -> server.enqueue(MockResponse().setBody(text)); expectFailure { g.plan(request(c,"bad$index")) } }
        assertEquals(bad.size,server.requestCount)
    }
    @Test fun responsesAndAnthropicAndGeminiToolCallsCannotBecomeText() {
        val bad=mapOf(
            ModelProtocol.OPENAI_RESPONSES to """{"status":"completed","output":[{"type":"function_call","name":"shell"}]}""",
            ModelProtocol.ANTHROPIC to """{"stop_reason":"end_turn","content":[{"type":"tool_use","name":"shell"}]}""",
            ModelProtocol.GEMINI to """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"shell"}}]}}]}"""
        )
        bad.forEach { (protocol,text) -> var failed=false; try { ModelProtocolCodec.response(protocol,text) } catch(_:Exception) { failed=true }; assertTrue(failed) }
    }
    @Test fun cancellingCoroutineCancelsSocketRequestAndDoesNotRetry()=runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c=config(ModelProtocol.OPENAI_CHAT); val g=gateway(c); val job=async { g.plan(request(c)) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) })
        withTimeout(2_000) { job.cancelAndJoin() }; assertEquals(1,server.requestCount)
    }
    @Test fun explicitCancelStopsActiveAttempt()=runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c=config(ModelProtocol.OPENAI_CHAT); val g=gateway(c); val job=async { expectFailure { g.plan(request(c)) } }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2,TimeUnit.SECONDS) })
        g.cancel("attempt"); withTimeout(2_000) { job.await() }; assertEquals(1,server.requestCount)
    }
    @Test fun timeoutIsBoundedAndDoesNotRetry()=runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c=config(ModelProtocol.OPENAI_CHAT)
        withTimeout(3_000) { expectFailure { gateway(c,150).plan(request(c)) } }; assertEquals(1,server.requestCount)
    }
    @Test fun responseSizeLimitRejectsBeforeVisibleOutput()=runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(OperitModelGateway.MAX_RESPONSE_BYTES+1)))
        val c=config(ModelProtocol.OPENAI_CHAT); val error=expectFailure { gateway(c).plan(request(c)) }
        assertTrue(error.message!!.contains("上限")); assertEquals(1,server.requestCount)
    }
    @Test fun staleFingerprintAndUnsupportedConfigurationNeverSendNetwork()=runBlocking {
        val c=config(ModelProtocol.OPENAI_CHAT); val g=gateway(c)
        expectFailure { g.plan(request(c).copy(configuration=c.snapshot("test-model").copy(fingerprint="old"))) }
        val unsupported=RpgModelConfigCodec.decode("test","""{"id":"test","apiProviderTypeId":"OPENAI_CODEX","modelName":"test-model","apiEndpoint":"https://example.com"}""")
        expectFailure { gateway(unsupported).plan(request(unsupported)) }; assertEquals(0,server.requestCount)
    }
    @Test fun blankKeyForExplicitLocalServiceDoesNotInventCredentials()=runBlocking {
        for(protocol in listOf(ModelProtocol.OPENAI_CHAT,ModelProtocol.ANTHROPIC)) {
            server.enqueue(MockResponse().setBody(response(protocol)))
            val c=config(protocol).copy(apiKey="")
            gateway(c).plan(request(c)); val sent=server.takeRequest(); assertNull(sent.getHeader("Authorization")); assertNull(sent.getHeader("x-api-key"))
            if(protocol==ModelProtocol.ANTHROPIC) assertEquals("2023-06-01",sent.getHeader("anthropic-version"))
        }
    }
    @Test fun nullLegacyFunctionCallDoesNotBecomeAnUnauthorizedTool() {
        val text=response(ModelProtocol.OPENAI_CHAT).replace("\"role\":\"assistant\"","\"role\":\"assistant\",\"function_call\":null,\"tool_calls\":null")
        assertEquals("可见正文",ModelProtocolCodec.response(ModelProtocol.OPENAI_CHAT,text).text)
    }
    @Test fun invalidCredentialCharactersAndReflectedCredentialsAreNotExposed()=runBlocking {
        val c=config(ModelProtocol.OPENAI_CHAT)
        val bad=c.copy(apiKey="test-secret\ninvalid")
        val invalid=expectFailure { gateway(bad).plan(request(bad)) }
        assertFalse(invalid.toString().contains("test-secret")); assertEquals(0,server.requestCount)
        server.enqueue(MockResponse().setBody(response(ModelProtocol.OPENAI_CHAT).replace("可见正文","test-secret")))
        val reflected=expectFailure { gateway(c).plan(request(c)) }
        assertFalse(reflected.toString().contains("test-secret")); assertTrue(reflected.message!!.contains("凭据")); assertEquals(1,server.requestCount)
    }
    @Test fun explicitlyReconfiguredProviderNamedDemoIsNeverSilentlyTheFakeGateway()=runBlocking {
        server.enqueue(MockResponse().setBody(response(ModelProtocol.OPENAI_CHAT)))
        val c=config(ModelProtocol.OPENAI_CHAT).copy(providerId="DEMO")
        assertEquals("可见正文",SelectedModelGateway(gateway(c)).plan(request(c)).text)
        assertEquals(1,server.requestCount)
    }
    @Test fun budgetDropsOldestHistoryAndPreservesRequiredState() {
        val original=buildJsonObject { put("input","当前问题"); put("state","必要状态"); put("mechanicalResult","已提交骰点30"); put("history",buildJsonArray { add("旧".repeat(9_000)); add("最新历史") }) }
        val trimmed=Json.parseToJsonElement(ModelProtocolCodec.budget("system",original.toString())).jsonObject
        assertEquals("当前问题",trimmed.getValue("input").jsonPrimitive.content); assertEquals(original["state"],trimmed["state"]); assertEquals(original["mechanicalResult"],trimmed["mechanicalResult"])
        assertEquals(listOf(JsonPrimitive("最新历史")),trimmed.getValue("history").jsonArray.toList())
    }
    @Test fun knownReasoningMarkupIsRemovedAndResidualMetadataRejected() {
        val good=response(ModelProtocol.OPENAI_CHAT).replace("可见正文","<think>PRIVATE</think>可见正文")
        assertEquals("可见正文",ModelProtocolCodec.response(ModelProtocol.OPENAI_CHAT,good).text)
        var failed=false; try { ModelProtocolCodec.response(ModelProtocol.OPENAI_CHAT,response(ModelProtocol.OPENAI_CHAT).replace("可见正文","<meta>PRIVATE</meta>正文")) } catch(_:Exception) { failed=true }; assertTrue(failed)
    }
    private suspend fun expectFailure(block:suspend()->Any):Exception {
        try { block() } catch(e:Exception) { return e }; throw AssertionError("Expected explicit failure")
    }
}
