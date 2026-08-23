package relay.memory

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LearnBatchPlannerTest {

    @Test
    fun keepsSessionsSeparateAndBoundsUserTurns() = runTest {
        val store = testStore()
        repeat(3) { turn ->
            store.capture(RawTurn(GRAPH_ASSISTANT, "user", "s1 user $turn", sessionId = "s1", ts = turn * 2L))
            store.capture(RawTurn(GRAPH_ASSISTANT, "assistant", "s1 assistant $turn", sessionId = "s1", ts = turn * 2L + 1))
        }
        store.capture(RawTurn(GRAPH_ASSISTANT, "user", "s2 user", sessionId = "s2", ts = 100))

        val batch = LearnBatchPlanner(maxUserTurns = 2).next(store, GRAPH_ASSISTANT)!!

        assertEquals("s1", batch.sessionId)
        assertEquals(2, batch.events.count { it.role == "user" })
        assertTrue(batch.events.all { it.sessionId == "s1" })
    }

    @Test
    fun nextBatchCarriesConsumedTailOnlyAsContext() = runTest {
        val store = testStore()
        val ids = mutableListOf<String>()
        repeat(3) { turn ->
            ids += store.capture(RawTurn(GRAPH_ASSISTANT, "user", "user $turn", sessionId = "s", ts = turn * 2L))
            ids += store.capture(RawTurn(GRAPH_ASSISTANT, "assistant", "assistant $turn", sessionId = "s", ts = turn * 2L + 1))
        }
        store.markConsumed(GRAPH_ASSISTANT, ids.take(2))

        val batch = LearnBatchPlanner(maxUserTurns = 1, contextTurns = 1)
            .next(store, GRAPH_ASSISTANT, "s")!!

        assertEquals(ids.take(2), batch.contextEventIds)
        assertEquals(ids.slice(2..3), batch.eventIds)
    }
}
