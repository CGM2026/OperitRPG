package com.ai.assistance.operit.rpg.model

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.rpg.core.*
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class OperitModelGateway(private val context: Context, private val manager: ModelConfigManager) : ModelGateway {
    private val active = ConcurrentHashMap<String, AIService>()
    override suspend fun plan(request: ModelRequest) = run(request)
    override suspend fun narrate(request: ModelRequest) = run(request)
    override suspend fun cancel(attemptId: String) { active.remove(attemptId)?.cancelStreaming() }

    private suspend fun run(request: ModelRequest): ModelReply = AppLogger.withPrivateLogging { withContext(Dispatchers.IO) {
        val original = manager.getModelConfig(request.configuration.configurationId)
            ?: error("模型配置已删除，请在本轮结束后重新选择")
        require(original.apiProviderTypeId == request.configuration.providerId &&
            getModelList(original.modelName).contains(request.configuration.modelId)) { "模型配置已改变" }
        require(configurationFingerprint(original, request.configuration.modelId) == request.configuration.fingerprint) { "模型连接设置已改变，请恢复原配置后继续本轮" }
        require(ApiProviderType.fromProviderTypeId(original.apiProviderTypeId) != null) { "首版暂不支持插件模型供应商" }
        require(original.apiProviderType !in setOf(ApiProviderType.MNN, ApiProviderType.LLAMA_CPP)) { "首版跑团仅支持云端模型或明确的离线演示" }
        val config = original.copy(modelName = request.configuration.modelId, enableToolCall = false,
            enableGoogleSearch = false, enableDeepSeekWebSearch = false, enableCodexWebSearch = false,
            enableSummary = false, enableDirectImageProcessing = false, enableDirectAudioProcessing = false,
            enableDirectVideoProcessing = false, hasCustomParameters = false, customParameters = "[]")
        val service = AIServiceFactory.createService(config, manager, context)
        check(active.putIfAbsent(request.attemptId, service) == null)
        try {
            withTimeout(60_000) {
                val history = listOf(PromptTurn(PromptTurnKind.SYSTEM, request.systemPrompt), PromptTurn(PromptTurnKind.USER, request.userPrompt))
                require(service.calculateInputTokens(history, emptyList()) <= 12_000) { "本轮上下文超过上限" }
                val output = StringBuilder()
                service.sendMessage(context = context, chatHistory = history,
                    modelParameters = listOf(ModelParameter("max_tokens", "最大输出", "max_tokens", defaultValue = request.maxOutputTokens,
                        currentValue = request.maxOutputTokens, isEnabled = true, valueType = ParameterValueType.INT)),
                    enableThinking = false, stream = false, availableTools = emptyList(),
                    preserveThinkInHistory = false, enableRetry = false).collect { chunk ->
                        require(output.length + chunk.length <= 64_000) { "模型输出过长" }
                        output.append(chunk)
                    }
                val visible = ChatUtils.extractThinkingContent(output.toString()).first.trim()
                require(visible.isNotEmpty()) { "模型未返回可见内容" }
                ModelReply(visible, Usage(service.inputTokenCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), service.outputTokenCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            }
        } finally { active.remove(request.attemptId); service.cancelStreaming(); service.release() }
    } }
}

fun configurationFingerprint(config: ModelConfigData, modelId: String): String = sha256(listOf(config.id, config.apiProviderTypeId, modelId, config.apiEndpoint, config.thinkingConfigurations, config.thinkingOptionId).joinToString("\n"))

/** Demo is an explicit selection; network errors never select it. */
class SelectedModelGateway(private val real: ModelGateway) : ModelGateway {
    private val demo = FakeModelGateway()
    override suspend fun plan(request: ModelRequest) = if (request.configuration.providerId == "DEMO") demo.plan(request) else real.plan(request)
    override suspend fun narrate(request: ModelRequest) = if (request.configuration.providerId == "DEMO") demo.narrate(request) else real.narrate(request)
    override suspend fun cancel(attemptId: String) = real.cancel(attemptId)
}
