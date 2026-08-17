package relay.orchestra

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.orchestra.yield.Resident
import relay.orchestra.yield.Scene
import relay.orchestra.yield.Utterance
import relay.orchestra.yield.projectIntoContext
import relay.orchestra.yield.speakOneLine

/**
 * Yield whose next speaker (or aside) is chosen by a director, not by the
 * current speaker and not by round-robin. The policy owns world state;
 * [Director] only runs: cue → speak/narrate → Scene → callback.
 */
sealed interface Cue {
    data class Speak(
        val speakerId: String,
        val tick: String,
        val channel: String = "public",
    ) : Cue

    data class Narrate(
        val text: String,
        val channel: String = "public",
        val speakerId: String = "system",
    ) : Cue
}

fun interface DirectorPolicy {
    fun next(): Cue?

    fun onSpoken(speakerId: String, text: String) {}

    fun onNarrated() {}
}

class Director(
    members: List<GroupChat.Member>,
    private val policy: DirectorPolicy,
    val scene: Scene = Scene(),
) {
    private val residents: Map<String, Resident>

    init {
        require(members.isNotEmpty()) { "Director needs at least one member" }
        residents = members.associate { member ->
            val agent = member.spawn(projectIntoContext(scene, member.project))
            member.id to Resident(member.id, agent, member.project)
        }
    }

    fun play(): Flow<TeamEvent> = flow {
        while (true) {
            when (val cue = policy.next() ?: break) {
                is Cue.Speak -> {
                    val resident = residents[cue.speakerId]
                        ?: error("DirectorPolicy picked unknown speaker '${cue.speakerId}'")
                    emit(TeamEvent.YieldStarted(cue.speakerId))
                    val text = resident.agent.speakOneLine(cue.tick) { event ->
                        emit(TeamEvent.YieldChild(cue.speakerId, event))
                    }
                    scene.append(Utterance(cue.speakerId, text, cue.channel))
                    policy.onSpoken(cue.speakerId, text)
                    emit(TeamEvent.Utterance(cue.speakerId, text, cue.channel))
                }
                is Cue.Narrate -> {
                    scene.append(Utterance(cue.speakerId, cue.text, cue.channel))
                    policy.onNarrated()
                    emit(TeamEvent.Utterance(cue.speakerId, cue.text, cue.channel))
                }
            }
        }
    }
}
