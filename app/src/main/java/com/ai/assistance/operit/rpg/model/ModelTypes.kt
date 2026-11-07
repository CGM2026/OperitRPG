package com.ai.assistance.operit.rpg.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Lower-case hex SHA-256 of the UTF-8 text. Model configuration fingerprints depend on these exact bytes. */
fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** One pinned model snapshot; [fingerprint] is derived by [configurationFingerprint] and never contains secrets. */
@Serializable data class ModelConfiguration(val configurationId: String, val providerId: String, val modelId: String, val fingerprint: String)
@Serializable data class Usage(val inputTokens: Int = 0, val outputTokens: Int = 0)
@Serializable data class ModelReply(val text: String, val usage: Usage = Usage())
data class ModelRequest(val attemptId: String, val configuration: ModelConfiguration, val systemPrompt: String, val userPrompt: String, val maxOutputTokens: Int)
