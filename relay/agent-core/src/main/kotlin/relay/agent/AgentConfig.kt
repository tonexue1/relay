package relay.agent

data class AgentConfig(
    val model: String,
    val systemPrompt: String = "",
    val maxTurns: Int = 8,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.Parallel,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutMillis: Long? = null,
)
