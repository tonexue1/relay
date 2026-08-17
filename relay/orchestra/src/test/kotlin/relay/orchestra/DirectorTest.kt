package relay.orchestra

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.agent.Agent
import relay.agent.AgentConfig

class DirectorTest {

    @Test
    fun narratesThenYieldsTheNamedSpeaker() = runTest {
        val providerA = ScriptedProvider(listOf { ScriptedProvider.text("line from A") })
        val providerB = ScriptedProvider(listOf { ScriptedProvider.text("B should not speak") })
        val director = Director(
            members = listOf(
                member("A", providerA),
                member("B", providerB),
            ),
            policy = ScriptedPolicy(
                Cue.Narrate("lights up"),
                Cue.Speak("A", "say your line", "stage"),
            ),
        )

        val spoken = director.play().toList().filterIsInstance<TeamEvent.Utterance>()
        assertEquals(listOf("system", "A"), spoken.map { it.speakerId })
        assertEquals(listOf("lights up", "line from A"), spoken.map { it.text })
        assertEquals("stage", spoken.last().channel)
        assertEquals("stage", director.scene.lines.last().channel)
        assertTrue(providerB.receivedRequests.isEmpty())
    }

    private fun member(id: String, provider: ScriptedProvider) = GroupChat.Member(id) { transform ->
        Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", maxTurns = 1),
            transformContext = transform,
        )
    }

    private class ScriptedPolicy(vararg cues: Cue) : DirectorPolicy {
        private val queue = ArrayDeque(cues.toList())
        override fun next(): Cue? = queue.removeFirstOrNull()
    }
}
