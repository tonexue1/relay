package relay.assistant.memory

import relay.memory.MemoryScope
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.EpisodeCommand
import relay.memory.api.MemoryBatch
import relay.memory.api.MemoryFault
import relay.memory.api.MemoryRuntime
import relay.memory.api.MemoryWriterKind
import relay.memory.api.RawEventDraft
import relay.memory.api.RawEventId
import relay.memory.api.RenderedText
import relay.memory.api.SourceRef
import relay.memory.api.SourceType
import relay.memory.ensureSpace

const val SPACE_ASSISTANT: String = "assistant"
const val OWNER_USER: String = "user"

suspend fun MemoryRuntime.ensureAssistantSpace() {
    ensureSpace(SPACE_ASSISTANT)
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
