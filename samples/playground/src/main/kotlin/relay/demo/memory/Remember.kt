package relay.demo.memory

import java.util.UUID
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
import relay.memory.MemoryScope
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryCodes
import relay.memory.api.MemoryRuntime
import relay.memory.api.MemoryWriterKind
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.api.StateCommand
import relay.memory.api.StateFieldSpec
import relay.memory.api.TargetLifecycle

internal data class StateProposal(
    val fieldId: String,
    val value: String,
    val rawEventIds: List<String> = emptyList(),
)

internal class AssistantMemoryPolicy(
    private val runtime: MemoryRuntime,
    private val spaceId: String,
    private val ownerId: String,
    private val clock: () -> ClockStamp = { ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()) },
) {
    suspend fun remember(proposal: StateProposal): String {
        val field = proposal.fieldId.trim()
        val value = proposal.value.trim()
        if (field.isEmpty() || value.isEmpty()) return "没有可记的内容"
        val registration = runtime.ensureStateField(StateFieldSpec(spaceId = spaceId, fieldId = field))
        val canonical = registration.fieldId
        val lifecycle = if (registration.created) TargetLifecycle.CANDIDATE else TargetLifecycle.CURRENT
        val first = commit(canonical, value, proposal.rawEventIds, lifecycle)
        if (first.ok) {
            return if (registration.created) {
                "新槽「$canonical」已建，记成待确认：$value"
            } else {
                "记下了 $canonical = $value"
            }
        }
        if (first.error?.code == MemoryCodes.USER_LOCK) {
            val retry = commit(canonical, value, proposal.rawEventIds, TargetLifecycle.CANDIDATE)
            if (retry.ok) return "该字段已被用户锁定，只能记成候选：$canonical = $value"
            return "没记成：${retry.error?.code ?: "COMMIT_FAILED"}"
        }
        return "没记成：${first.error?.code ?: "COMMIT_FAILED"}"
    }

    private suspend fun commit(
        fieldId: String,
        value: String,
        rawEventIds: List<String>,
        lifecycle: TargetLifecycle,
    ) = runtime.commit(
        MemoryBatch(
            spaceId = spaceId,
            ownerId = ownerId,
            writerKind = MemoryWriterKind.EXTRACTOR,
            writerId = "assistant-remember",
            writerRunId = UUID.randomUUID().toString(),
            commands = listOf(
                StateCommand(
                    fieldId = fieldId,
                    payload = JsonObject(mapOf("value" to JsonPrimitive(value))),
                    rendered = RenderedText(value),
                    sources = if (rawEventIds.isEmpty()) {
                        listOf(SourceRef(SourceType.HOST_TXN, UUID.randomUUID().toString()))
                    } else {
                        rawEventIds.map { SourceRef(SourceType.RAW_EVENT, it) }
                    },
                    validFrom = clock(),
                    targetLifecycle = lifecycle,
                    scope = MemoryScope.PROFILE,
                ),
            ),
        ),
    )
}

fun MemoryRuntime.rememberTools(
    spaceId: String,
    ownerId: String,
    rawEventIds: () -> List<String> = { emptyList() },
): List<Tool> {
    val policy = AssistantMemoryPolicy(this, spaceId, ownerId)
    return listOf(
        FunTool(
            name = "remember_state",
            description = "记住用户的稳定事实（过敏、住址等）。一次性打算不要记。field_id 可以是规范名或别名。",
            parameters = rememberSchema,
        ) { raw ->
            val args = Json.parseToJsonElement(raw) as JsonObject
            policy.remember(
                StateProposal(
                    args["field_id"]?.jsonPrimitive?.content.orEmpty(),
                    args["value"]?.jsonPrimitive?.content.orEmpty(),
                    rawEventIds(),
                ),
            )
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
