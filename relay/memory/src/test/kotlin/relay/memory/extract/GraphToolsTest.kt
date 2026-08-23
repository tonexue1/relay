package relay.memory.extract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.GRAPH_ASSISTANT
import relay.memory.agent.dayTools
import relay.memory.agent.graphTools
import relay.memory.agent.nightTools

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GraphToolsTest {

    @Test
    fun namesAreStable() {
        val names = testStore().graphTools(GRAPH_ASSISTANT).map { it.def.name }
        assertEquals(
            listOf(
                "memory_ingest",
                "memory_query",
                "memory_facts",
                "memory_recent",
                "memory_neighborhood",
                "memory_merge_nodes",
                "memory_forget",
                "memory_pending_review",
                "memory_resolve_review",
            ),
            names,
        )
    }

    @Test
    fun dayAndNightSubsets() {
        val store = testStore()
        assertEquals(listOf("memory_query", "memory_facts"), store.dayTools(GRAPH_ASSISTANT).map { it.def.name })
        assertEquals(
            listOf(
                "memory_ingest",
                "memory_facts",
                "memory_recent",
                "memory_neighborhood",
                "memory_merge_nodes",
            ),
            store.nightTools(GRAPH_ASSISTANT).map { it.def.name },
        )
    }

    @Test
    fun ingestReportsUnknownPredicateWithoutWriting() = runTest {
        val store = testStore()
        val ingest = store.graphTools(GRAPH_ASSISTANT).single { it.def.name == "memory_ingest" }
        val out = ingest.execute(
            "c1",
            """{"triples":[{"s":"用户","p":"teleports_to","o":"月球"},{"s":"用户","p":"likes","o":"茶"}]}""",
        )
        val errors = Json.parseToJsonElement(out).jsonObject["errors"]!!.jsonArray
        assertEquals(1, errors.size)
        assertEquals("teleports_to", errors.single().jsonObject["p"]!!.jsonPrimitive.content)
        val facts = store.graphTools(GRAPH_ASSISTANT).single { it.def.name == "memory_facts" }
        val hit = Json.parseToJsonElement(facts.execute("c2", "{}")).jsonObject["facts"]!!.jsonArray
        assertEquals(1, hit.size)
        assertEquals("茶", hit.single().jsonObject["o"]!!.jsonPrimitive.content)
    }

    @Test
    fun queryIsLiteralAndMergeExpiresDrop() = runTest {
        val store = testStore()
        val tools = store.graphTools(GRAPH_ASSISTANT).associateBy { it.def.name }
        tools.getValue("memory_ingest").execute(
            "c1",
            """{"triples":[{"s":"用户","p":"allergic_to","o":"花生"},{"s":"用户","p":"plans","o":"离职"}]}""",
        )
        val hotpot = Json.parseToJsonElement(
            tools.getValue("memory_query").execute("c2", """{"text":"火锅"}"""),
        ).jsonObject["facts"]!!.jsonArray
        assertTrue(hotpot.isEmpty())
        val peanut = Json.parseToJsonElement(
            tools.getValue("memory_query").execute("c3", """{"text":"花生"}"""),
        ).jsonObject["facts"]!!.jsonArray
        assertTrue(peanut.any { it.jsonObject["p"]!!.jsonPrimitive.content == "allergic_to" })

        tools.getValue("memory_merge_nodes").execute("c4", """{"keep":"跳槽","drop":"离职"}""")
        val live = Json.parseToJsonElement(
            tools.getValue("memory_facts").execute("c5", """{"p":"plans"}"""),
        ).jsonObject["facts"]!!.jsonArray
        assertEquals(listOf("跳槽"), live.map { it.jsonObject["o"]!!.jsonPrimitive.content })
    }
}
