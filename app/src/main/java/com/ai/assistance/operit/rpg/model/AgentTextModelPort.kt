package com.ai.assistance.operit.rpg.model

import android.content.Context
import com.ai.assistance.operit.rpg.agent.contract.ContextPacket
import com.ai.assistance.operit.rpg.agent.contract.TextModelPort
import com.ai.assistance.operit.rpg.agent.contract.TextModelReply
import com.ai.assistance.operit.rpg.agent.contract.Usage
import com.ai.assistance.operit.rpg.core.ModelRequest
import okhttp3.OkHttpClient

/** Binds one explicitly selected local configuration; never discovers keys outside this app. */
class AgentTextModelPort(
    private val configurations: suspend () -> List<RpgModelConfig>,
    private val fingerprint: String,
    client: OkHttpClient = OkHttpClient()
) : TextModelPort {
    constructor(context: Context, fingerprint: String) : this(
        { RpgModelConfigStore.get(context.applicationContext).list() }, fingerprint
    )
    private val gateway = OperitModelGateway({ id -> configurations().singleOrNull { it.id == id } }, client)
    override suspend fun generate(attemptId: String, packet: ContextPacket): TextModelReply {
        require(fingerprint.isNotBlank()) { "请先明确选择本机模型配置" }
        val matches = configurations().filter { it.protocol != null && it.unsupportedReason == null }.flatMap { config ->
            config.modelNames.map { config.snapshot(it) }
        }.filter { it.fingerprint == fingerprint }
        require(matches.size == 1) { "所选模型配置已删除或改变，请明确重新绑定模型" }
        val reply = gateway.generateText(ModelRequest(attemptId, matches.single(), packet.system, packet.input, packet.maxOutputTokens))
        return TextModelReply(reply.text, Usage(reply.usage.inputTokens, reply.usage.outputTokens))
    }
    override fun cancel(attemptId: String) = gateway.cancelNow(attemptId)
}
