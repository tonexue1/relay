package relay.assistant.memory

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.api.ClockDomain
import relay.memory.api.ClockStamp
import relay.memory.api.SearchMemories
import relay.memory.engine.HashedEmbedder
import relay.memory.engine.SqliteLedgerRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantMemoryPolicyTest {

    private fun runtime() = SqliteLedgerRuntime(ApplicationProvider.getApplicationContext())

    @Test
    fun rememberAddsFactAndSearchFindsIt() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val facts = AssistantFacts(runtime, SPACE_ASSISTANT, OWNER_USER)
        val out = facts.remember("用户喜欢吃西红柿")
        assertTrue(out.startsWith("记下了"))

        val found = facts.search("西红柿")
        assertTrue("喜欢吃西红柿" in found)
    }

    @Test
    fun duplicateTextIsNotInsertedAgain() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val facts = AssistantFacts(runtime, SPACE_ASSISTANT, OWNER_USER)
        facts.remember("用户花生过敏")
        val again = facts.remember("用户花生过敏")
        assertTrue("已经有了" in again)
    }

    @Test
    fun newerLinkedFactHidesOldInLatestOnlySearch() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val embedder = HashedEmbedder()
        val facts = AssistantFacts(runtime, SPACE_ASSISTANT, OWNER_USER, embedder)
        facts.remember("用户喜欢吃西红柿")
        val oldId = runtime.searchMemories(
            SearchMemories(
                spaceId = SPACE_ASSISTANT,
                ownerId = OWNER_USER,
                query = "西红柿",
                queryVector = embedder.embed("西红柿"),
                embeddingModelId = embedder.modelId,
                at = ClockStamp(ClockDomain.WALL_CLOCK, System.currentTimeMillis()),
                latestOnly = false,
            ),
        ).single().id
        facts.remember("用户不再喜欢吃西红柿", listOf(oldId))

        val latest = facts.search("西红柿")
        assertTrue("不再喜欢" in latest)
        assertFalse(latest.lines().any { "喜欢吃西红柿" in it && "不再" !in it })
    }

    @Test
    fun toolsWriteFacts() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val tools = runtime.rememberTools(SPACE_ASSISTANT, OWNER_USER)
        val remember = tools.single { it.def.name == MemoryToolNames.REMEMBER }
        val search = tools.single { it.def.name == MemoryToolNames.SEARCH_MEMORIES }
        val message = remember.execute("c1", """{"memory":"用户住在杭州"}""")
        assertTrue("记下了" in message)
        val found = search.execute("c2", """{"query":"杭州"}""")
        assertTrue("杭州" in found)
    }

    @Test
    fun emptyRememberDoesNotWrite() = runTest {
        val runtime = runtime()
        runtime.ensureAssistantSpace()
        val facts = AssistantFacts(runtime, SPACE_ASSISTANT, OWNER_USER)
        assertEquals("没有可记的内容", facts.remember("  "))
        assertTrue(runtime.listItems(SPACE_ASSISTANT, OWNER_USER).none { it.kind.name == "FACT" })
    }
}
