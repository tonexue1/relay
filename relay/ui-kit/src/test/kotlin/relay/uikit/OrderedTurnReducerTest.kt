package relay.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrderedTurnReducerTest {
    @Test
    fun `continuation opens assistant turn without synthetic user bubble`() {
        val turns = OrderedTurnReducer.beginContinuation(emptyList(), id = "choice")

        assertEquals(1, turns.size)
        assertEquals("assistant", turns.single().role)
        assertFalse(turns.single().complete)
    }
}
