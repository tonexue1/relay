package relay.agent

/**
 * How tool calls from a single assistant message are executed.
 *
 * Parallel is the default, matching pi-agent-core: preflight sequentially, then run
 * allowed tools concurrently. Results are still written back in assistant source order.
 */
enum class ToolExecutionMode { Parallel, Sequential }
