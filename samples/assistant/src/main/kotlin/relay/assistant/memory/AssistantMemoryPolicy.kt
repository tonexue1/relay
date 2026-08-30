package relay.assistant.memory

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class AssistantMemoryPolicy(
    private val runtime: MemoryRuntime,
    private val spaceId: String,
    private val ownerId: String,
    private val clock: () -> ClockStamp = { ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()) },
    private val writerId: String = "assistant-remember",
) {
    suspend fun remember(proposal: StateProposal): RememberOutcome {
        val field = proposal.fieldId.trim()
        val value = proposal.value.trim()
        if (field.isEmpty() || value.isEmpty()) {
            return RememberOutcome(ok = false, message = "没有可记的内容")
        }
        val registration = runtime.ensureStateField(StateFieldSpec(spaceId = spaceId, fieldId = field))
        val canonical = registration.fieldId
        val lifecycle = if (registration.created) TargetLifecycle.CANDIDATE else TargetLifecycle.CURRENT
        val first = commit(canonical, value, proposal.rawEventIds, lifecycle)
        if (first.ok) {
            return outcome(canonical, value, candidate = lifecycle == TargetLifecycle.CANDIDATE, created = registration.created)
        }
        if (first.error?.code == MemoryCodes.USER_LOCK) {
            val retry = commit(canonical, value, proposal.rawEventIds, TargetLifecycle.CANDIDATE)
            if (retry.ok) {
                return RememberOutcome(
                    ok = true,
                    message = "该字段已被用户锁定，只能记成候选：$canonical = $value",
                    fieldId = canonical,
                    candidate = true,
                )
            }
            return RememberOutcome(ok = false, message = "没记成：${retry.error?.code ?: "COMMIT_FAILED"}")
        }
        return RememberOutcome(ok = false, message = "没记成：${first.error?.code ?: "COMMIT_FAILED"}")
    }

    private fun outcome(fieldId: String, value: String, candidate: Boolean, created: Boolean): RememberOutcome {
        val message = when {
            created -> "新槽「$fieldId」已建，记成待确认：$value"
            candidate -> "已记成候选：$fieldId = $value"
            else -> "记下了 $fieldId = $value"
        }
        return RememberOutcome(ok = true, message = message, fieldId = fieldId, candidate = candidate)
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
            writerId = writerId,
            writerRunId = UUID.randomUUID().toString(),
            commands = listOf(
                StateCommand(
                    fieldId = fieldId,
                    payload = JsonObject(mapOf("value" to JsonPrimitive(value))),
                    rendered = RenderedText(value),
                    sources = sources(rawEventIds),
                    validFrom = clock(),
                    targetLifecycle = lifecycle,
                    scope = MemoryScope.PROFILE,
                ),
            ),
        ),
    )

    private fun sources(rawEventIds: List<String>): List<SourceRef> {
        val raw = rawEventIds.map { SourceRef(SourceType.RAW_EVENT, it) }
        if (raw.isNotEmpty()) return raw
        return listOf(SourceRef(SourceType.HOST_TXN, UUID.randomUUID().toString()))
    }
}
