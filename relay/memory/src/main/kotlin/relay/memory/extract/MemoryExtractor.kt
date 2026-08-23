package relay.memory.extract

import relay.memory.Fact
import relay.memory.ExtractResult

interface MemoryExtractor {
    suspend fun extract(
        graphId: String,
        dialogue: String,
        rawEventIds: List<String>,
        priorFacts: List<Fact> = emptyList(),
    ): ExtractResult
}
