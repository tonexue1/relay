package relay.memory.dream

import relay.agent.Agent
import relay.agent.AgentConfig
import relay.llm.Provider
import relay.llm.provider.DeepSeek
import relay.memory.MemoryStore
import relay.memory.agent.nightTools

class AgentConsolidator(
    private val provider: Provider,
    private val store: MemoryStore,
    private val model: String = DeepSeek.CHAT,
) : MemoryConsolidator {
    override suspend fun consolidate(graphId: String, since: Long): ConsolidationReport {
        val agent = Agent(
            provider = provider,
            config = AgentConfig(
                model = model,
                systemPrompt = DREAM_SYSTEM,
                maxTurns = 8,
                timeoutMillis = 90_000,
            ),
            tools = store.nightTools(graphId),
        )
        val text = agent.run(
            "since=$since。查看 recent 和需要的 neighborhood，把近义节点 merge_nodes " +
                "（例如 离职→跳槽）。不要编造新事实。做完用中文说改了什么。",
        ).text.orEmpty()
        return ConsolidationReport(summary = text)
    }
}
