package relay.assistant.memory

import relay.llm.RelayLlmException
import relay.llm.embed.TextEmbedder
import relay.memory.api.AddMemory
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.MemoryRuntime
import relay.memory.api.SearchMemories
import relay.memory.engine.HashedEmbedder

class AssistantFacts(
    private val runtime: MemoryRuntime,
    private val spaceId: String,
    private val ownerId: String,
    private val embedder: TextEmbedder = HashedEmbedder(),
    private val clock: () -> ClockStamp = { ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()) },
) {
    suspend fun remember(text: String, linkedMemoryIds: List<String> = emptyList(), rawEventIds: List<String> = emptyList()): String {
        val memory = text.trim()
        if (memory.isEmpty()) return "没有可记的内容"
        val vector = try {
            embedder.embed(memory)
        } catch (e: RelayLlmException) {
            return "向量失败：${e.message}"
        }
        val added = runtime.addMemory(
            AddMemory(
                spaceId = spaceId,
                ownerId = ownerId,
                text = memory,
                linkedMemoryIds = linkedMemoryIds,
                vector = vector,
                embeddingModelId = embedder.modelId,
                rawEventIds = rawEventIds,
                writerId = "assistant-remember",
                at = clock(),
            ),
        )
        return if (added.created) {
            "记下了（${added.id}）：$memory"
        } else {
            "这条已经有了（${added.id}）"
        }
    }

    suspend fun search(query: String): String {
        val q = query.trim()
        val vector = try {
            if (q.isEmpty()) null else embedder.embed(q)
        } catch (e: RelayLlmException) {
            return "向量失败：${e.message}"
        }
        val hits = runtime.searchMemories(
            SearchMemories(
                spaceId = spaceId,
                ownerId = ownerId,
                query = q,
                queryVector = vector,
                embeddingModelId = embedder.modelId,
                at = clock(),
            ),
        )
        if (hits.isEmpty()) return "没有相关记忆"
        return hits.joinToString("\n") { "${it.id}: ${it.text}" }
    }
}
