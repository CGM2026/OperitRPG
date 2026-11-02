package com.ai.assistance.operit.rpg.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RpgModelConfigCodecTest {
    private val legacy="""{"id":"legacy","name":"旧配置","apiProviderType":"DEEPSEEK","apiProviderTypeId":"DEEPSEEK","apiKey":"private-key","apiEndpoint":"https://api.deepseek.com/v1/chat/completions","modelName":"deepseek-chat, deepseek-reasoner","thinkingConfigurations":"[]","thinkingOptionId":"","unknownFuture":{"keep":[1,2,3]},"customParameters":"[]"}"""
    @Test fun publishedLegacySnapshotFingerprintRemainsExactlyCompatible() {
        val config=RpgModelConfigCodec.decode("legacy",legacy)
        assertEquals(ModelProtocol.OPENAI_CHAT,config.protocol); assertEquals("DEEPSEEK",config.providerId); assertEquals(listOf("deepseek-chat","deepseek-reasoner"),config.modelNames)
        assertEquals("597dbbedfb1e2d0dff223c67fb9504153ab40db90597095113cd7863e505a91a",configurationFingerprint(config,"deepseek-chat"))
        assertEquals("legacy",config.snapshot("deepseek-chat").configurationId)
        assertEquals(config.raw,Json.parseToJsonElement(legacy)); assertFalse(config.toString().contains("private-key"))
    }
    @Test fun explicitEditRetainsUnknownFieldsAndLegacyIdProviderThinking() {
        val old=RpgModelConfigCodec.decode("legacy",legacy)
        val updated=RpgModelConfigCodec.decode("legacy",RpgModelConfigCodec.encode(ModelConfigEdit("legacy","新名称","new-key",old.endpoint,old.modelNames,ModelProtocol.OPENAI_CHAT),"legacy",old.raw))
        assertEquals(old.raw["unknownFuture"],updated.raw["unknownFuture"]); assertEquals(old.providerId,updated.providerId); assertEquals(old.id,updated.id)
        assertEquals(configurationFingerprint(old,"deepseek-chat"),configurationFingerprint(updated,"deepseek-chat")); assertEquals("new-key",updated.apiKey)
    }
    @Test fun explicitProtocolConversionCannotSilentlyReusePinnedLegacyFingerprint() {
        val old=RpgModelConfigCodec.decode("legacy",legacy)
        val changed=RpgModelConfigCodec.decode("legacy",RpgModelConfigCodec.encode(ModelConfigEdit("legacy","旧配置","private-key",old.endpoint,old.modelNames,ModelProtocol.OPENAI_RESPONSES),"legacy",old.raw))
        assertEquals(old.id,changed.id); assertEquals(old.providerId,changed.providerId)
        assertNotEquals(configurationFingerprint(old,"deepseek-chat"),configurationFingerprint(changed,"deepseek-chat"))
    }
    @Test fun unknownAndOauthProvidersArePreservedUntilExplicitReconfiguration() {
        for(provider in listOf("OPENAI_CODEX","plugin:private","OPENCODE","MNN")) {
            val source=legacy.replace("DEEPSEEK",provider); val config=RpgModelConfigCodec.decode("legacy",source)
            assertNull(config.protocol); assertNotNull(config.unsupportedReason); assertEquals(Json.parseToJsonElement(source),config.raw)
            val explicit=RpgModelConfigCodec.decode("legacy",RpgModelConfigCodec.encode(ModelConfigEdit("legacy","重新配置","new",config.endpoint,config.modelNames,ModelProtocol.OPENAI_RESPONSES),"legacy",config.raw))
            assertEquals(ModelProtocol.OPENAI_RESPONSES,explicit.protocol); assertEquals(provider,explicit.providerId)
        }
    }
    @Test fun advancedLegacySettingsRequireAnExplicitSimpleReconfiguration() {
        for(extra in listOf("\"useMultipleApiKeys\":true","\"hasCustomParameters\":true","\"customHeaders\":\"{\\\"x-auth\\\":\\\"secret\\\"}\"")) {
            val source=legacy.dropLast(1)+","+extra+"}"; val config=RpgModelConfigCodec.decode("legacy",source)
            assertNull(config.protocol); assertTrue(config.unsupportedReason!!.contains("重新配置")); assertEquals(Json.parseToJsonElement(source),config.raw)
        }
    }
    @Test fun malformedConfigurationIsNotReplacedOrAssignedAnotherProvider() {
        val invalid=RpgModelConfigCodec.decode("broken","not-json"); assertNull(invalid.protocol); assertNotNull(invalid.unsupportedReason)
        val missing=RpgModelConfigCodec.decode("missing",null); assertNull(missing.protocol)
        val wrongId=RpgModelConfigCodec.decode("wrong",legacy); assertNull(wrongId.protocol); assertEquals(Json.parseToJsonElement(legacy),wrongId.raw)
    }
    @Test fun unsafeUrlInputsAreRejectedWithoutRewritingHostOrScheme() {
        for(url in listOf("file:///tmp/key","https://user:secret@example.com/v1","https://example.com/v1?key=secret","https://example.com/v1#fragment","not-a-url")) {
            var failed=false; try { validateEndpoint(url) } catch(_:IllegalArgumentException) { failed=true }; assertTrue(url,failed)
        }
        assertEquals("http",validateEndpoint("http://192.168.1.10:8080/v1").scheme)
        assertEquals("192.168.1.10",validateEndpoint("http://192.168.1.10:8080/v1").host)
    }
    @Test fun legacyEndpointCompletionAndGeminiOriginBehaviorRemainStable() {
        val old=RpgModelConfigCodec.decode("legacy",legacy)
        assertEquals("https://api.deepseek.com/v1/chat/completions",modelEndpoint(old,"deepseek-chat").toString())
        assertEquals("https://example.com/v1/chat/completions",modelEndpoint(old.copy(endpoint="https://example.com"),"deepseek-chat").toString())
        assertEquals("https://example.com/custom",modelEndpoint(old.copy(endpoint="https://example.com/custom#"),"deepseek-chat").toString())
        val gemini=RpgModelConfigCodec.decode("g","""{"id":"g","name":"g","apiProviderTypeId":"GOOGLE","apiEndpoint":"https://example.com/ignored/legacy/path","modelName":"gemini-test"}""")
        assertEquals("https://example.com/v1beta/models/gemini-test:generateContent",modelEndpoint(gemini,"gemini-test").toString())
    }
}
