package com.ai.assistance.operit.rpg.core

import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString

class InvalidPlanException(message: String) : IllegalArgumentException(message)
object PlanParser {
    fun parse(text: String): Plan {
        try {
            require(text.length <= 4096 && !text.contains(Regex("(?i)<\\s*/?\\s*(tool|think|analysis|meta)|```")))
            // A strict lexical pass rejects duplicate keys as well as concatenated documents.
            val inputKeys = topLevelKeys(text)
            require(inputKeys.distinct().size == inputKeys.size)
            val o = RpgJson.instance.parseToJsonElement(text) as JsonObject
            fun string(key: String): String { val v = o.getValue(key) as JsonPrimitive; require(v.isString); return v.content }
            val version = o.getValue("schema_version") as JsonPrimitive
            require(!version.isString && version.content == "1")
            val kind = string("kind")
            val keys = when (kind) {
                "action" -> setOf("schema_version", "kind", "action_id", "requires_confirmation", "public_intent")
                "dialogue" -> setOf("schema_version", "kind", "npc_id")
                "ooc" -> setOf("schema_version", "kind")
                "clarify" -> setOf("schema_version", "kind", "question")
                else -> error("UNKNOWN_KIND")
            }
            require(o.keys == keys)
            return when (kind) {
                "action" -> { val id = string("action_id"); require(RainyDock.actions.any { it.id == id }); val intent = string("public_intent"); require(intent.length in 1..200); val confirm = o.getValue("requires_confirmation") as JsonPrimitive; require(!confirm.isString); Plan(PlanKind.ACTION, id, confirm.boolean, intent) }
                "dialogue" -> { val npc = string("npc_id"); require(npc in setOf("keeper", "clerk", "boatman")); Plan(PlanKind.DIALOGUE, npcId = npc) }
                "ooc" -> Plan(PlanKind.OOC)
                else -> { val question = string("question"); require(question.length in 1..300); Plan(PlanKind.CLARIFY, question = question) }
            }
        } catch (e: Exception) { throw InvalidPlanException("INVALID_MODEL_OUTPUT") }
    }
    private fun topLevelKeys(text: String): List<String> {
        val keys = mutableListOf<String>(); var depth = 0; var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val start = i; i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    require(i < text.length)
                    var after = i + 1; while (after < text.length && text[after].isWhitespace()) after++
                    if (depth == 1 && after < text.length && text[after] == ':') keys += RpgJson.instance.decodeFromString<String>(text.substring(start, i + 1))
                }
            }; i++
        }
        return keys
    }
}
