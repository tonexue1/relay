package relay.assistant.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import relay.agent.FunTool
import relay.agent.Tool
import relay.memory.api.MemoryRuntime

object MemoryToolNames {
    const val REMEMBER_STATE = "remember_state"
}

fun MemoryRuntime.rememberTools(
    spaceId: String,
    ownerId: String,
    rawEventIds: () -> List<String> = { emptyList() },
): List<Tool> {
    val policy = AssistantMemoryPolicy(this, spaceId, ownerId)
    return listOf(
        FunTool(
            name = MemoryToolNames.REMEMBER_STATE,
            description = "记住用户的稳定事实（过敏、住址等）。一次性打算不要记。field_id 可以是规范名或别名。",
            parameters = rememberSchema,
        ) { raw ->
            val args = Json.parseToJsonElement(raw) as JsonObject
            val fieldId = args["field_id"]?.jsonPrimitive?.content.orEmpty()
            val value = args["value"]?.jsonPrimitive?.content.orEmpty()
            policy.remember(StateProposal(fieldId, value, rawEventIds())).message
        },
    )
}

private val rememberSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("field_id") {
            put("type", "string")
            put("description", "规范名或别名，如 allergies 或 过敏")
        }
        putJsonObject("value") {
            put("type", "string")
            put("description", "要记住的当前值，如 花生")
        }
    }
    putJsonArray("required") {
        add(JsonPrimitive("field_id"))
        add(JsonPrimitive("value"))
    }
}
