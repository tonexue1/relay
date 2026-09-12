package relay.agent

/**
 * Failures raised by the agent loop itself, as opposed to [relay.llm.RelayLlmException]
 * from the underlying provider.
 */
sealed class AgentException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class AlreadyRunning : AgentException("Agent is already running")

    class MaxToolBatchesExceeded(val maxToolBatches: Int) :
        AgentException("Agent exceeded maxToolBatches=$maxToolBatches while the model still requested tools")

    class CannotContinue(message: String) : AgentException(message)
}
