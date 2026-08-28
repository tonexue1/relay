package relay.memory

import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.EpisodeCommand
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryFault
import relay.memory.api.MemoryRuntime
import relay.memory.api.MemoryWriterKind
import relay.memory.api.OverwritePolicy
import relay.memory.api.RawEventDraft
import relay.memory.api.RawEventId
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.api.StateFieldSpec
import relay.memory.api.StateSchemaSnapshot

suspend fun MemoryRuntime.ensureAssistantSpace() {
    registerStateSchema(
        StateSchemaSnapshot(
            spaceId = SPACE_ASSISTANT,
            clockDomain = ClockDomain.WALL_CLOCK,
            fields = listOf(
                StateFieldSpec(
                    spaceId = SPACE_ASSISTANT,
                    fieldId = "allergies",
                    overwritePolicy = OverwritePolicy.USER_LOCK,
                ),
                StateFieldSpec(spaceId = SPACE_ASSISTANT, fieldId = "location"),
            ),
        ),
    )
    putFieldAlias(SPACE_ASSISTANT, "过敏", "allergies")
}

suspend fun MemoryRuntime.captureTurn(
    spaceId: String,
    ownerId: String,
    domain: ClockDomain,
    role: String,
    text: String,
    sessionId: String,
    taskScopeId: String = "",
    writerId: String = "host",
): RawEventId {
    val rawId = capture(
        RawEventDraft(
            spaceId = spaceId,
            ownerId = ownerId,
            role = role,
            content = text,
            clockDomain = domain,
            sessionId = sessionId,
            taskScopeId = taskScopeId,
        ),
    )
    val now = ClockStamp(domain, System.currentTimeMillis())
    val result = commit(
        MemoryBatch(
            spaceId = spaceId,
            ownerId = ownerId,
            writerKind = MemoryWriterKind.HOST,
            writerId = writerId,
            writerRunId = sessionId.ifBlank { rawId },
            commands = listOf(
                EpisodeCommand(
                    idempotencyKey = "raw:$rawId",
                    occurredAt = now,
                    rendered = RenderedText("$role: $text"),
                    sources = listOf(SourceRef(SourceType.RAW_EVENT, rawId)),
                    scope = MemoryScope.SESSION,
                    scopeId = sessionId,
                ),
            ),
            commitRawIds = listOf(rawId),
        ),
    )
    if (!result.ok) {
        val error = result.error
        throw MemoryFault(error?.code ?: "COMMIT_FAILED", error?.message ?: "commit failed")
    }
    return rawId
}
