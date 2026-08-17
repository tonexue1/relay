package relay.orchestra

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.orchestra.yield.Resident
import relay.orchestra.yield.RoundRobin
import relay.orchestra.yield.Scene
import relay.orchestra.yield.Stage
import relay.orchestra.yield.TurnPolicy
import relay.orchestra.yield.projectIntoContext

class YieldTest {

    @Test
    fun roundRobinWritesSceneAndRewindsPrivateMessages() = runTest {
        val scene = Scene()
        val providerA = ScriptedProvider(listOf { ScriptedProvider.text("hello from A") })
        val providerB = ScriptedProvider(listOf { ScriptedProvider.text("hello from B") })
        val agentA = speaker("A", scene, providerA)
        val agentB = speaker("B", scene, providerB)
        val stage = Stage(
            scene = scene,
            residents = listOf(
                Resident("A", agentA) { fullScene(it) },
                Resident("B", agentB) { fullScene(it) },
            ),
            policy = RoundRobin(listOf("A", "B")),
        )

        stage.continueScene().toList()

        assertEquals(listOf("A", "B"), scene.lines.map { it.speakerId })
        assertEquals(listOf("hello from A", "hello from B"), scene.lines.map { it.text })
        assertFalse(agentA.state.messages.any { it.content?.contains("hello from B") == true })
        assertFalse(agentB.state.messages.any { it.content?.contains("hello from A") == true })
        assertEquals(emptyList(), agentA.state.messages.map { it.role })
        assertEquals(emptyList(), agentB.state.messages.map { it.role })

        val bRequest = providerB.receivedRequests.single().messages
            .joinToString("\n") { it.content.orEmpty() }
        assertTrue(bRequest.contains("hello from A"), "B must see projected Scene")
    }

    @Test
    fun projectCanDropASecretLine() = runTest {
        val scene = Scene()
        scene.append(relay.orchestra.yield.Utterance("secret", "do not leak"))
        scene.append(relay.orchestra.yield.Utterance("A", "public line"))
        val providerC = ScriptedProvider(listOf { ScriptedProvider.text("ok") })
        val agentC = Agent(
            provider = providerC,
            config = AgentConfig(model = "fake-model", maxTurns = 1),
            transformContext = projectIntoContext(scene) { s ->
                s.lines
                    .filter { it.speakerId != "secret" }
                    .joinToString("\n") { "${it.speakerId}: ${it.text}" }
            },
        )
        val stage = Stage(
            scene = scene,
            residents = listOf(Resident("C", agentC) { "" }),
            policy = RoundRobin(listOf("C")),
        )

        stage.continueScene().toList()

        val sent = providerC.receivedRequests.single().messages
            .joinToString("\n") { it.content.orEmpty() }
        assertFalse(sent.contains("do not leak"))
        assertTrue(sent.contains("public line"))
    }

    @Test
    fun userInterjectionLetsPolicyPickA() = runTest {
        val providerA = ScriptedProvider(listOf { ScriptedProvider.text("A after user") })
        val providerB = ScriptedProvider(listOf { ScriptedProvider.text("B should not speak") })
        val chat = GroupChat(
            members = listOf(
                GroupChat.Member("A") { tx ->
                    Agent(
                        provider = providerA,
                        config = AgentConfig(model = "fake-model", maxTurns = 1),
                        transformContext = tx,
                    )
                },
                GroupChat.Member("B") { tx ->
                    Agent(
                        provider = providerB,
                        config = AgentConfig(model = "fake-model", maxTurns = 1),
                        transformContext = tx,
                    )
                },
            ),
            policy = TurnPolicy { _, userJustSpoke -> if (userJustSpoke) "A" else null },
        )

        val events = chat.prompt("hey everyone").toList()
        val spoken = events.filterIsInstance<TeamEvent.Utterance>()
        assertEquals(listOf("user", "A"), spoken.map { it.speakerId })
        assertEquals("A after user", spoken.last().text)
        assertTrue(providerB.receivedRequests.isEmpty())
        assertTrue(
            providerA.receivedRequests.single().messages
                .any { it.content?.contains("hey everyone") == true },
        )
    }

    @Test
    fun roundRobinResetsWhenUserSpeaksAgain() = runTest {
        val providerA = ScriptedProvider(
            listOf(
                { ScriptedProvider.text("A1") },
                { ScriptedProvider.text("A2") },
            ),
        )
        val providerB = ScriptedProvider(
            listOf(
                { ScriptedProvider.text("B1") },
                { ScriptedProvider.text("B2") },
            ),
        )
        val chat = GroupChat(
            members = listOf(
                GroupChat.Member("A") { tx ->
                    Agent(
                        provider = providerA,
                        config = AgentConfig(model = "fake-model", maxTurns = 1),
                        transformContext = tx,
                    )
                },
                GroupChat.Member("B") { tx ->
                    Agent(
                        provider = providerB,
                        config = AgentConfig(model = "fake-model", maxTurns = 1),
                        transformContext = tx,
                    )
                },
            ),
        )

        chat.prompt("first").toList()
        val second = chat.prompt("second").toList()
            .filterIsInstance<TeamEvent.Utterance>()
            .map { it.speakerId }
        assertEquals(listOf("user", "A", "B"), second)
        assertEquals(2, providerA.receivedRequests.size)
    }

    private fun speaker(
        id: String,
        scene: Scene,
        provider: ScriptedProvider,
    ): Agent = Agent(
        provider = provider,
        config = AgentConfig(model = "fake-model", maxTurns = 1),
        transformContext = projectIntoContext(scene) { fullScene(it) },
    )

    private fun fullScene(scene: Scene): String =
        scene.lines.joinToString("\n") { "${it.speakerId}: ${it.text}" }
}
