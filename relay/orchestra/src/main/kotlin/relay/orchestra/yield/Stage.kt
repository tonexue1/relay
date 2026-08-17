package relay.orchestra.yield

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.agent.Agent
import relay.agent.AgentEvent
import relay.llm.model.Message
import relay.llm.model.Role
import relay.orchestra.TeamEvent

internal const val SPEAK_TICK = "你的下一句。"

/**
 * Runs one Yield loop: policy picks a resident, they speak one line, Scene
 * grows, the tick is rewound so public lines never land in [Agent.state].
 */
class Stage(
    val scene: Scene,
    private val residents: List<Resident>,
    private val policy: TurnPolicy,
    private val speakTick: String = SPEAK_TICK,
) {
    init {
        require(residents.isNotEmpty()) { "Stage needs at least one resident" }
        val ids = residents.map { it.id }
        require(ids.size == ids.toSet().size) { "Resident ids must be unique" }
    }

    fun prompt(userText: String, userId: String = "user"): Flow<TeamEvent> = flow {
        scene.append(Utterance(userId, userText))
        emit(TeamEvent.Utterance(userId, userText))
        emitAllTurns(userJustSpoke = true)
    }

    fun continueScene(): Flow<TeamEvent> = flow {
        emitAllTurns(userJustSpoke = false)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<TeamEvent>.emitAllTurns(
        userJustSpoke: Boolean,
    ) {
        var justSpoke = userJustSpoke
        while (true) {
            val speakerId = policy.next(scene, justSpoke) ?: break
            justSpoke = false
            val resident = residents.firstOrNull { it.id == speakerId }
                ?: error("TurnPolicy picked unknown speaker '$speakerId'")
            emit(TeamEvent.YieldStarted(speakerId))
            val text = resident.agent.speakOneLine(speakTick) { event ->
                emit(TeamEvent.YieldChild(speakerId, event))
            }
            scene.append(Utterance(speakerId, text))
            emit(TeamEvent.Utterance(speakerId, text))
        }
    }
}

suspend fun Agent.speakOneLine(
    tick: String,
    onEvent: suspend (AgentEvent) -> Unit = {},
): String {
    var text = ""
    prompt(tick).collect { event ->
        onEvent(event)
        if (event is AgentEvent.MessageEnd && event.message.role == Role.ASSISTANT) {
            text = event.message.content.orEmpty()
        }
    }
    rewindTick(this, tick)
    return text
}

/** Drop the opening tick + the assistant line so only private messages remain. */
internal fun rewindTick(agent: Agent, tick: String = SPEAK_TICK) {
    val msgs = agent.state.messages
    if (msgs.size < 2) return
    val last = msgs.last()
    val prev = msgs[msgs.size - 2]
    if (last.role == Role.ASSISTANT && prev.role == Role.USER && prev.content == tick) {
        agent.state.messages = msgs.dropLast(2)
    }
}

fun projectIntoContext(
    scene: Scene,
    project: (Scene) -> String,
): suspend (List<Message>) -> List<Message> = { private ->
    val visible = project(scene)
    val projected = if (visible.isBlank()) emptyList() else listOf(Message.user(visible))
    projected + private
}
