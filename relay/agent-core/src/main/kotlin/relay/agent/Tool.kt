package relay.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import relay.llm.model.ToolDef

/**
 * A callable tool the agent can dispatch.
 *
 * [execute] must throw on failure. The loop catches that and reports an error tool
 * result to the model -- do not encode errors in the returned string.
 */
interface Tool {
    val def: ToolDef
    val label: String get() = def.name
    val executionMode: ToolExecutionMode? get() = null
    val waitsForUser: Boolean get() = false

    suspend fun execute(toolCallId: String, argumentsJson: String): String

    /**
     * Executes a tool and optionally emits data for lifecycle-event consumers.
     *
     * [ToolOutput.eventData] never enters the model transcript. This keeps presentation data
     * available to clients without making the model parse or retain it as a tool result.
     */
    suspend fun executeOutput(toolCallId: String, argumentsJson: String): ToolOutput =
        ToolOutput(execute(toolCallId, argumentsJson))
}

data class ToolOutput(
    val content: String,
    val eventData: JsonObject? = null,
)

/**
 * JSON Schema for a function with no arguments.
 *
 * OpenAI-compatible APIs reject `{}` ("Invalid schema"): parameters must be an object
 * schema, even when [properties] is empty.
 */
val EmptyObjectSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {}
}

/**
 * Convenience [Tool] for a lambda. Schema defaults to [EmptyObjectSchema] (no arguments).
 */
fun FunTool(
    name: String,
    description: String? = null,
    parameters: JsonObject = EmptyObjectSchema,
    label: String = name,
    executionMode: ToolExecutionMode? = null,
    block: suspend (argumentsJson: String) -> String,
): Tool = object : Tool {
    override val def: ToolDef = ToolDef(name, description, parameters)
    override val label: String = label
    override val executionMode: ToolExecutionMode? = executionMode
    override suspend fun execute(toolCallId: String, argumentsJson: String): String =
        block(argumentsJson)
}
