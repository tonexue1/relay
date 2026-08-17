package relay.orchestra

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import relay.agent.Agent
import relay.agent.Tool
import relay.orchestra.call.AgentTool
import relay.orchestra.call.asLead

/**
 * Lead agent whose tools are disposable workers. Parallel Calls reuse core's
 * [relay.agent.ToolExecutionMode.Parallel] — this class does not start its own pool.
 */
class Supervisor(
    spawnLead: (List<Tool>) -> Agent,
    workers: List<WorkerSpec>,
    artifacts: ArtifactStore,
    ledger: TeamLedger,
    private val events: Channel<TeamEvent> = Channel(Channel.UNLIMITED),
) {
    private val lead: Agent = spawnLead(
        workers.map { spec ->
            AgentTool(
                workerId = spec.id,
                spawn = spec.spawn,
                artifacts = artifacts,
                ledger = ledger,
                events = events,
                description = spec.description,
            )
        },
    )

    fun prompt(input: String): Flow<TeamEvent> = lead.asLead(input, events)
}
