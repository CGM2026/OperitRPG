package com.ai.assistance.operit.rpg.agent.tools

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.memory.*
import com.ai.assistance.operit.rpg.agent.rules.digest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object StrictJson {
    val json=Json { ignoreUnknownKeys=false; isLenient=false; coerceInputValues=false }
    fun parse(text:String):JsonElement {
        require(text.toByteArray().size<=262144) { "JSON_LIMIT" }
        // A lexical pass tracks every object independently, before kotlinx can collapse duplicate keys.
        val stack=ArrayDeque<MutableSet<String>?>(); var i=0
        while(i<text.length) {
            when(text[i]) {
                '{' -> { require(stack.size<64) { "JSON_DEPTH_LIMIT" }; stack.addLast(mutableSetOf()) }
                '[' -> { require(stack.size<64) { "JSON_DEPTH_LIMIT" }; stack.addLast(null) }
                '}',']' -> { require(stack.isNotEmpty()); stack.removeLast() }
                '"' -> { val start=i; i++; while(i<text.length) { if(text[i]=='\\')i+=2 else if(text[i]=='"')break else i++ }; require(i<text.length); var next=i+1; while(next<text.length && text[next].isWhitespace())next++; if(next<text.length && text[next]==':') { val key=json.decodeFromString<String>(text.substring(start,i+1)); require(stack.lastOrNull()?.add(key)==true) { "DUPLICATE_JSON_KEY" } } }
            }; i++
        }
        return json.parseToJsonElement(text)
    }
    inline fun <reified T> decode(text:String):T { parse(text); return json.decodeFromString(text) }
}
object AgentProtocol {
    fun parse(text:String):AgentStep {
        val obj=StrictJson.parse(text).jsonObject
        val step=StrictJson.json.decodeFromJsonElement<AgentStep>(obj)
        require(step.version==1) { "UNKNOWN_AGENT_VERSION" }
        val expected=when(step.kind) { "read_tools" -> setOf("version","kind","calls"); "propose_action" -> setOf("version","kind","proposal"); "answer","clarify" -> setOf("version","kind","text"); else -> error("UNKNOWN_AGENT_STEP") }
        require(obj.keys==expected) { "INVALID_STEP_FIELDS" }
        when(step.kind) { "read_tools" -> require(!step.calls.isNullOrEmpty() && step.calls.size<=3); "propose_action" -> require(step.proposal!=null); else -> require(!step.text.isNullOrBlank() && step.text.length<=8000) }
        return step
    }
}
class ToolRegistry(private val rules:RuleRepository,private val memories:MemoryRepository) {
    fun execute(call:ToolCall,c:Campaign,module:ValidatedModule,role:RoleContext):ToolResult {
        require(role.campaignId==c.id) { "ROLE_SCOPE" }
        val a=call.arguments
        fun keys(required:Set<String>,optional:Set<String> = emptySet()) { require(a.keys.containsAll(required) && a.keys.all { it in required || it in optional }) { "TOOL_ARGUMENTS" } }
        fun str(key:String)=a.getValue(key).jsonPrimitive.content.also { require(it.length<=2000) }
        fun ids(key:String)=a[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().also { require(it.size<=20) }
        var sources=emptyList<SourceRef>()
        val output=when(call.name) {
            "rules.search" -> { keys(setOf("query")); val found=rules.search(role,c.module,str("query"),5).filter { canRead(role,it.visibility) }.take(5); sources=found.map { it.source }; Json.encodeToString(found) }
            "rules.read" -> { keys(setOf("sourceId","anchor")); val found=rules.read(role,c.module,str("sourceId"),str("anchor")); require(found!=null && canRead(role,found.visibility)) { "RULE_NOT_FOUND" }; sources=listOf(found.source); Json.encodeToString(found) }
            "memory.search" -> { keys(setOf("query"),setOf("entityIds")); val found=memories.search(role,str("query"),ids("entityIds"),5).filter { it.campaignId==c.id && it.status=="ACTIVE" && canRead(role,it.visibility) }.take(5); Json.encodeToString(found) }
            "world.inspect" -> {
                keys(setOf("entityIds")); val wanted=ids("entityIds"); require(wanted.isNotEmpty())
                val entities=wanted.map { id ->
                    when {
                        id=="player" && role.audience in setOf(Audience.GM,Audience.PUBLIC) -> buildJsonObject { put("locationId",c.world.locationId); put("resources",Json.encodeToJsonElement(c.world.resources)); put("skills",Json.encodeToJsonElement(c.world.skills)) }
                        id=="world" -> buildJsonObject { put("gameTime",c.world.gameTime) }
                        module.world.locations.any { it.id==id } -> Json.encodeToJsonElement(module.world.locations.single { it.id==id })
                        c.world.npcs.any { it.id==id } -> {
                            val n=c.world.npcs.single { it.id==id }
                            if(role.audience==Audience.GM || role.audience==Audience.NPC && role.subjectId==id)Json.encodeToJsonElement(n)
                            else { require(n.locationId==c.world.locationId) { "ENTITY_UNAVAILABLE" }; Json.encodeToJsonElement(PublicNpc(n.id,n.name,n.locationId,n.status)) }
                        }
                        c.world.facts.any { it.id==id } -> {
                            val fact=c.world.facts.single { it.id==id }
                            val allowed=if(role.audience==Audience.NPC)c.world.knowledge.any { it.knowerId==role.subjectId && it.factId==id } || fact.visibility.audience==Audience.NPC && fact.visibility.subjectId==role.subjectId else canRead(role,fact.visibility)
                            require(allowed) { "ENTITY_UNAVAILABLE" }; Json.encodeToJsonElement(fact)
                        }
                        c.world.objectives.any { it.id==id } -> Json.encodeToJsonElement(c.world.objectives.single { it.id==id })
                        c.world.items.any { it.id==id } -> { val item=c.world.items.single { it.id==id }; require(role.audience==Audience.GM || item.ownerId=="player" && role.audience==Audience.PUBLIC || item.ownerId==role.subjectId) { "ENTITY_UNAVAILABLE" }; Json.encodeToJsonElement(item) }
                        else -> error("ENTITY_UNAVAILABLE")
                    }
                }; JsonArray(entities).toString()
            }
            "npc.read" -> { keys(setOf("npcId")); val id=str("npcId"); val npc=c.world.npcs.singleOrNull { it.id==id }; require(npc!=null) { "ENTITY_UNAVAILABLE" }; if(role.audience==Audience.GM || role.audience==Audience.NPC && role.subjectId==id) Json.encodeToString(npc) else { require(npc.locationId==c.world.locationId) { "ENTITY_UNAVAILABLE" }; Json.encodeToString(PublicNpc(npc.id,npc.name,npc.locationId,npc.status)) } }
            else -> error("UNKNOWN_TOOL")
        }
        require(output.toByteArray().size<=8192) { "TOOL_OUTPUT_BUDGET" }
        return ToolResult(call.invocationId,1,digest(call.name+StrictJson.json.encodeToString(a)),c.revision,sources,output)
    }
}
