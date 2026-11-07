package com.ai.assistance.operit.rpg.agent.engine

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import com.ai.assistance.operit.rpg.agent.memory.PlanningSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** Explicit offline demonstration. This class is never selected by a failed real-model request. */
class DemoTextModelPort(private val module:ValidatedModule):TextModelPort {
    override fun cancel(attemptId:String) = Unit
    override suspend fun generate(attemptId:String,packet:ContextPacket):TextModelReply {
        if(packet.purpose=="narrator")return TextModelReply("行动已经记录。你可以查看检定结果，继续调查、交谈、移动或等待。",Usage(0,0))
        val snapshotJson=packet.input.substringBefore("\nRules:").let { if(it.startsWith("Chapter:"))it.substringAfter('\n') else it }
        val snapshot=StrictJson.decode<PlanningSnapshot>(snapshotJson)
        val world=WorldState(snapshot.location.id,snapshot.resources,snapshot.skills,snapshot.npcs.map { NpcState(it.id,it.name,it.locationId,it.status,"","") },emptyList(),emptyList(),snapshot.objectives,emptyList(),gameTime=snapshot.gameTime)
        val player=packet.input.substringAfter("\nPlayer:").substringBefore("\nTool results:")
        val toolJson=packet.input.substringAfter("\nTool results:").substringBefore("\nMemory:")
        val results=StrictJson.decode<List<ToolResult>>(toolJson)
        val step=when(results.size) {
            0 -> AgentStep(1,"read_tools",calls=listOf(ToolCall(UUID.randomUUID().toString(),"rules.search",buildJsonObject { put("query","行动检定 移动 等待 调查 人物") })))
            1 -> AgentStep(1,"read_tools",calls=listOf(ToolCall(UUID.randomUUID().toString(),"world.inspect",buildJsonObject { putJsonArray("entityIds") { add(world.locationId) } })))
            else -> propose(world,player)
        }
        return TextModelReply(StrictJson.json.encodeToString(step).let { encoded ->
            val obj=StrictJson.parse(encoded).jsonObject
            val fields=when(step.kind) { "read_tools" -> setOf("version","kind","calls"); "propose_action" -> setOf("version","kind","proposal"); else -> setOf("version","kind","text") }
            JsonObject(obj.filterKeys { it in fields }).toString()
        },Usage(0,0))
    }
    private fun propose(w:WorldState,text:String):AgentStep {
        val current=module.world.locations.single { it.id==w.locationId }
        val destination=module.world.locations.firstOrNull { it.id!=w.locationId && (text.contains(it.title) || text.contains(it.id)) }
        val rule=module.rules.evidence.first { it.ruleId.endsWith(":travel") }
        val proposal=when {
            destination!=null -> {
                if(destination.id !in current.exits)return AgentStep(1,"answer",text="离线演示：此处不能直接抵达${destination.title}，请沿相邻地点移动。")
                ActionProposal("move",listOf(destination.id),null,"normal",listOf(rule.ruleId),listOf(Condition("location",targetId=w.locationId)),listOf(Effect("move",destination.id,source=rule.source),Effect("time","world",amount=10,source=rule.source)),emptyList(),publicSummary="前往${destination.title}")
            }
            text.contains("等待") || text.contains("休息") -> ActionProposal("wait",listOf("world"),null,"normal",listOf(rule.ruleId),emptyList(),listOf(Effect("time","world",amount=10,source=rule.source)),emptyList(),publicSummary="等待十分钟")
            text.contains("交谈") || text.contains("询问") || text.contains("说服") -> {
                val npc=w.npcs.firstOrNull { it.locationId==w.locationId && text.contains(it.name) }?:w.npcs.firstOrNull { it.locationId==w.locationId }
                if(npc==null)return AgentStep(1,"answer",text="这里没有可交谈的人物。")
                val e=module.rules.evidence.first { it.ruleId.endsWith(":people") }
                ActionProposal("negotiate",listOf(npc.id),"交涉","normal",listOf(e.ruleId),emptyList(),listOf(Effect("npc_relation",npc.id,key="trust",amount=5,source=e.source),Effect("time","world",amount=10,source=e.source)),listOf(Effect("time","world",amount=10,source=e.source)),publicSummary="与${npc.name}交谈，尝试增进信任")
            }
            else -> {
                val a=module.world.actions.firstOrNull { action -> action.conditions.any { it.type=="location" && it.targetId==w.locationId } }?:return AgentStep(1,"answer",text="离线演示仅支持调查、相邻地点移动、交谈和等待。自由行动请使用已配置模型验收。")
                ActionProposal(a.actionType,a.targetIds,a.skill,a.difficulty,listOf(a.ruleId),a.conditions,a.successEffects,a.failureEffects,publicSummary=a.title)
            }
        }
        return AgentStep(1,"propose_action",proposal=proposal)
    }
}
