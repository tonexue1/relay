package relay.memory.extract

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.llm.model.Role
import relay.memory.GRAPH_ASSISTANT
import relay.memory.TripleDraft
import relay.memory.agent.recalling

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantAgentHookTest {

    @Test
    fun factsAreProjectedIntoProviderCallButNotWrittenToTranscript() = runTest {
        val store = testStore()
        store.ingest(listOf(TripleDraft(GRAPH_ASSISTANT, "用户", "allergic_to", "花生")))
        val provider = RecordingProvider("避开花生。")
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", systemPrompt = "你是私人助理"),
            contextAugmenters = listOf(store.recalling(GRAPH_ASSISTANT)),
        )

        agent.prompt("今晚能吃花生吗").toList()

        val sent = provider.streams.single().messages
        assertEquals("你是私人助理", sent.first { it.role == Role.SYSTEM }.content)
        assertTrue(
            sent.any {
                it.role == Role.USER &&
                    it.content.orEmpty().contains("已知事实") &&
                    it.content.orEmpty().contains("花生")
            },
        )
        assertEquals(listOf(Role.USER, Role.ASSISTANT), agent.state.messages.map { it.role })
        assertEquals("今晚能吃花生吗", agent.state.messages.first().content)
        assertFalse(agent.state.messages.any { it.content.orEmpty().contains("已知事实") })
        assertEquals("避开花生。", agent.state.messages.last().content)
    }

    @Test
    fun systemInjectionWouldBeStrippedSoRememberingMustUseUserRole() = runTest {
        val provider = RecordingProvider("ok")
        val agent = Agent(
            provider = provider,
            config = AgentConfig(model = "fake-model", systemPrompt = "人设"),
            transformContext = { msgs -> listOf(relay.llm.model.Message.system("不该出现")) + msgs },
        )
        agent.prompt("hi").toList()
        val systems = provider.streams.single().messages.filter { it.role == Role.SYSTEM }
        assertEquals(listOf("人设"), systems.map { it.content })
    }
}
