package relay.orchestra

import relay.agent.Agent

/** How to spawn one disposable worker. [id] is the tool name the lead sees. */
data class WorkerSpec(
    val id: String,
    val description: String,
    val spawn: () -> Agent,
    val maxTurns: Int = 4,
)
