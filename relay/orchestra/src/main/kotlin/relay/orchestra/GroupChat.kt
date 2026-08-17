package relay.orchestra

import kotlinx.coroutines.flow.Flow
import relay.agent.Agent
import relay.llm.model.Message
import relay.orchestra.yield.Resident
import relay.orchestra.yield.RoundRobin
import relay.orchestra.yield.SPEAK_TICK
import relay.orchestra.yield.Scene
import relay.orchestra.yield.Stage
import relay.orchestra.yield.TurnPolicy
import relay.orchestra.yield.projectIntoContext

/**
 * Yield with a selector. Default policy is round-robin; swap [policy] for an
 * LLM picker later. Public lines live on [scene], not in any agent's messages.
 */
class GroupChat(
    members: List<Member>,
    policy: TurnPolicy? = null,
    val scene: Scene = Scene(),
    speakTick: String = SPEAK_TICK,
) {
    data class Member(
        val id: String,
        val project: (Scene) -> String = { s ->
            s.lines.joinToString("\n") { "${it.speakerId}: ${it.text}" }
        },
        val spawn: (transformContext: suspend (List<Message>) -> List<Message>) -> Agent,
    )

    private val stage: Stage

    init {
        require(members.isNotEmpty()) { "GroupChat needs at least one member" }
        val residents = members.map { member ->
            val agent = member.spawn(projectIntoContext(scene, member.project))
            Resident(member.id, agent, member.project)
        }
        stage = Stage(
            scene = scene,
            residents = residents,
            policy = policy ?: RoundRobin(members.map { it.id }),
            speakTick = speakTick,
        )
    }

    fun prompt(userText: String): Flow<TeamEvent> = stage.prompt(userText)

    fun continueScene(): Flow<TeamEvent> = stage.continueScene()
}
