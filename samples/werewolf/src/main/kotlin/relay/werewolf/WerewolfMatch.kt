package relay.werewolf

import kotlinx.coroutines.flow.Flow
import relay.agent.Agent
import relay.llm.model.Message
import relay.orchestra.Director
import relay.orchestra.GroupChat
import relay.orchestra.TeamEvent
import relay.orchestra.yield.Scene
import relay.werewolf.engine.WerewolfEngine

/** Wires seats onto [Director]. The engine is the policy; it does not run the loop. */
class WerewolfMatch(
    val engine: WerewolfEngine,
    spawn: (playerId: String, transform: suspend (List<Message>) -> List<Message>) -> Agent,
) {
    private val director = Director(
        members = engine.players.map { player ->
            GroupChat.Member(
                id = player.id,
                project = { scene -> engine.project(player.id, scene.lines) },
                spawn = { transform -> spawn(player.id, transform) },
            )
        },
        policy = engine,
    )

    val scene: Scene get() = director.scene

    fun play(): Flow<TeamEvent> = director.play()
}
