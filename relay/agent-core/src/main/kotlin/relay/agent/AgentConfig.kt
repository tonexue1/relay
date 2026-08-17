package relay.agent

data class AgentConfig(
    val model: String,
    val systemPrompt: String = "",
    /** Max real tool batches. The next tool call is not run; it receives a "summarize now" result. */
    val maxTurns: Int = 8,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.Parallel,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutMillis: Long? = null,
)
