package relay.memory.dream

data class ConsolidationReport(
    val summary: String = "",
)

fun interface MemoryConsolidator {
    suspend fun consolidate(graphId: String, since: Long): ConsolidationReport
}
