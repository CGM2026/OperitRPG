package com.ai.assistance.operit.rpg.model

import kotlinx.serialization.json.*

object ModelProtocolCodec {
    fun request(protocol: ModelProtocol, model: String, system: String, user: String, maxTokens: Int): JsonObject = buildJsonObject {
        when (protocol) {
            ModelProtocol.OPENAI_CHAT -> {
                put("model", model); put("stream", false); put("max_tokens", maxTokens)
                put("messages", buildJsonArray { add(message("system", system)); add(message("user", user)) })
            }
            ModelProtocol.OPENAI_RESPONSES -> {
                put("model", model); put("stream", false); put("store", false); put("max_output_tokens", maxTokens)
                put("instructions", system); put("input", buildJsonArray { add(message("user", user)) })
            }
            ModelProtocol.ANTHROPIC -> {
                put("model", model); put("stream", false); put("max_tokens", maxTokens); put("system", system)
                put("messages", buildJsonArray { add(message("user", user)) })
            }
            ModelProtocol.GEMINI -> {
                put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) }) })
                put("contents", buildJsonArray { add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) }) }) })
                put("generationConfig", buildJsonObject { put("maxOutputTokens", maxTokens) })
            }
        }
    }
    private fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    fun response(protocol: ModelProtocol, text: String): ModelReply {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["error"] != null && root["error"] != JsonNull) throw ModelRequestException("模型接口报告错误")
        val output = StringBuilder()
        fun append(value: String) { require(output.length + value.length <= 64_000); output.append(value) }
        val usage: Usage
        when (protocol) {
            ModelProtocol.OPENAI_CHAT -> {
                val choices = root.getValue("choices").jsonArray; require(choices.size == 1)
                val choice = choices.single().jsonObject
                require(choice["finish_reason"]?.jsonPrimitive?.content in setOf("stop", "end_turn"))
                val message = choice.getValue("message").jsonObject
                require(message["function_call"].let { it == null || it == JsonNull } && message["tool_calls"].let { it == null || it == JsonNull || it.jsonArray.isEmpty() })
                val content = message.getValue("content")
                if (content is JsonPrimitive) { require(content.isString); append(content.content) }
                else content.jsonArray.forEach { part -> require(part.jsonObject.getValue("type").jsonPrimitive.content == "text"); append(part.jsonObject.getValue("text").jsonPrimitive.content) }
                usage = usage(root["usage"], "prompt_tokens", "completion_tokens")
            }
            ModelProtocol.OPENAI_RESPONSES -> {
                require(root["status"]?.jsonPrimitive?.content == "completed")
                root.getValue("output").jsonArray.forEach { item ->
                    val value = item.jsonObject
                    when (value.getValue("type").jsonPrimitive.content) {
                        "reasoning" -> Unit
                        "message" -> value.getValue("content").jsonArray.forEach { part -> require(part.jsonObject.getValue("type").jsonPrimitive.content == "output_text"); append(part.jsonObject.getValue("text").jsonPrimitive.content) }
                        else -> throw ModelRequestException("接口返回了未授权的工具或非文本输出")
                    }
                }
                usage = usage(root["usage"], "input_tokens", "output_tokens")
            }
            ModelProtocol.ANTHROPIC -> {
                require(root["stop_reason"]?.jsonPrimitive?.content == "end_turn")
                root.getValue("content").jsonArray.forEach { part ->
                    val value = part.jsonObject
                    when (value.getValue("type").jsonPrimitive.content) {
                        "text" -> append(value.getValue("text").jsonPrimitive.content)
                        "thinking", "redacted_thinking" -> Unit
                        else -> throw ModelRequestException("接口返回了未授权的工具或非文本输出")
                    }
                }
                usage = usage(root["usage"], "input_tokens", "output_tokens")
            }
            ModelProtocol.GEMINI -> {
                val candidates = root.getValue("candidates").jsonArray; require(candidates.size == 1)
                val candidate = candidates.single().jsonObject; require(candidate["finishReason"]?.jsonPrimitive?.content == "STOP")
                candidate.getValue("content").jsonObject.getValue("parts").jsonArray.forEach { part ->
                    val value = part.jsonObject; require(!value.containsKey("functionCall"))
                    if (value["thought"]?.jsonPrimitive?.booleanOrNull != true) append(value.getValue("text").jsonPrimitive.content)
                }
                usage = usage(root["usageMetadata"], "promptTokenCount", "candidatesTokenCount")
            }
        }
        val visible = output.toString().replace(Regex("(?is)<think\\b[^>]*>.*?</think\\s*>"), "").trim()
        require(visible.isNotEmpty() && !visible.contains(Regex("(?i)<\\s*/?\\s*(think|analysis|meta|tool)\\b")))
        return ModelReply(visible, usage)
    }
    private fun usage(element: JsonElement?, input: String, output: String): Usage {
        if (element == null || element == JsonNull) return Usage()
        fun number(key: String): Int { val value = element.jsonObject[key]?.jsonPrimitive?.long ?: return 0; require(value >= 0); return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        return Usage(number(input), number(output))
    }
}
