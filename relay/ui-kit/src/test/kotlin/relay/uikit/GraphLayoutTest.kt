package relay.uikit

import androidx.compose.ui.geometry.Offset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

class GraphLayoutTest {
    @Test
    fun `focus is centered and layout is deterministic`() {
        val nodes = listOf(GraphNode("b", "B"), GraphNode("a", "A"), GraphNode("c", "C"))
        val first = graphLayout(nodes, "b", 400f, 300f)
        val second = graphLayout(nodes.reversed(), "b", 400f, 300f)
        assertEquals(Offset(200f, 150f), first["b"])
        assertEquals(first, second)
        assertNotEquals(first["a"], first["c"])
    }
}
