package relay.orchestra.call

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import relay.agent.Agent
import relay.orchestra.TeamEvent

/**
 * Observe a lead [Agent] and merge worker [TeamEvent]s from the shared channel
 * [AgentTool] writes to. Does not change agent-core.
 */
internal fun Agent.asLead(
    userInput: String,
    events: ReceiveChannel<TeamEvent>,
): Flow<TeamEvent> = channelFlow {
    val childJob = launch {
        for (child in events) send(child)
    }
    try {
        prompt(userInput).collect { send(TeamEvent.Lead(it)) }
    } finally {
        childJob.cancel()
        while (true) {
            val leftover = events.tryReceive().getOrNull() ?: break
            send(leftover)
        }
    }
}
