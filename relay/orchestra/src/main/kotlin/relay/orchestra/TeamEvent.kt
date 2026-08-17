package relay.orchestra

import relay.agent.AgentEvent

/**
 * Team-level events. Wraps [AgentEvent] so a single agent loop stays unaware of
 * workers or speakers.
 */
sealed interface TeamEvent {
    data class Lead(val event: AgentEvent) : TeamEvent

    data class CallStarted(val workerId: String, val task: String) : TeamEvent

    data class CallChild(val workerId: String, val event: AgentEvent) : TeamEvent

    data class CallEnded(val workerId: String, val result: WorkerReturn) : TeamEvent

    data class YieldStarted(val speakerId: String) : TeamEvent

    data class YieldChild(val speakerId: String, val event: AgentEvent) : TeamEvent

    data class Utterance(
        val speakerId: String,
        val text: String,
        val channel: String = "public",
    ) : TeamEvent
}
