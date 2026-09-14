package relay.assistant.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import relay.agent.FunTool
import relay.agent.Tool
import relay.llm.embed.TextEmbedder
import relay.memory.api.MemoryRuntime
import relay.memory.engine.HashedEmbedder

object MemoryToolNames {
    const val REMEMBER = "remember"
    const val SEARCH_MEMORIES = "search_memories"
}

fun MemoryRuntime.rememberTools(
    spaceId: String,
    ownerId: String,
    rawEventIds: () -> List<String> = { emptyList() },
    embedder: TextEmbedder = HashedEmbedder(),
): List<Tool> {
    val facts = AssistantFacts(this, spaceId, ownerId, embedder)
    return listOf(
        FunTool(
            name = MemoryToolNames.SEARCH_MEMORIES,
            description = "按语义搜索已记住的事实。改旧事实前先搜，把旧记忆 id 传给 remember。",
            parameters = searchSchema,
        ) { raw ->
            val args = Json.parseToJsonElement(raw) as JsonObject
            facts.search(args["query"]?.jsonPrimitive?.content.orEmpty())
        },
        FunTool(
            name = MemoryToolNames.REMEMBER,
            description = "追加一条事实记忆，不覆盖旧条。变化写成转变句（如「用户不再喜欢西红柿」），并传入 search_memories 得到的旧 id。",
            parameters = rememberSchema,
        ) { raw ->
            val args = Json.parseToJsonElement(raw) as JsonObject
            val text = args["memory"]?.jsonPrimitive?.content.orEmpty()
            val linked = (args["linked_memory_ids"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            facts.remember(text, linked, rawEventIds())
        },
    )
}

private val searchSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("description", "要找的事实，如 西红柿 或 过敏")
        }
    }
    putJsonArray("required") { add(JsonPrimitive("query")) }
}

private val rememberSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("memory") {
            put("type", "string")
            put("description", "一句完整事实，如 用户花生过敏 或 用户不再喜欢西红柿")
        }
        putJsonObject("linked_memory_ids") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", "被这条取代的旧记忆 id")
        }
    }
    putJsonArray("required") { add(JsonPrimitive("memory")) }
}
