package relay.assistant.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.memory.MemoryScope
import relay.memory.RecallContext
import relay.memory.api.LifecycleState

class MemoryVisibilityTest {
    private val current = RecallContext(sessionId = "session-a", taskScopeId = "task-a")

    @Test
    fun `confirmed profile is recallable in any chat`() {
        assertTrue(
            MemoryVisibility.recallable(MemoryScope.PROFILE, LifecycleState.ACTIVE, "", current),
        )
        assertFalse(MemoryVisibility.isolated(MemoryScope.PROFILE, "", current))
        assertEquals("资料 · 已确认", MemoryVisibility.label(MemoryScope.PROFILE, LifecycleState.ACTIVE, ""))
    }

    @Test
    fun `legacy session facts stay in inventory but not current recall`() {
        assertFalse(
            MemoryVisibility.recallable(MemoryScope.SESSION, LifecycleState.CANDIDATE, "legacy", current),
        )
        assertTrue(MemoryVisibility.isolated(MemoryScope.SESSION, "legacy", current))
        assertEquals("历史隔离 · 候选", MemoryVisibility.label(MemoryScope.SESSION, LifecycleState.CANDIDATE, "legacy"))
    }

    @Test
    fun `other task is isolated until host allows cross task`() {
        assertFalse(
            MemoryVisibility.recallable(MemoryScope.TASK, LifecycleState.CANDIDATE, "task-b", current),
        )
        assertTrue(
            MemoryVisibility.recallable(
                MemoryScope.TASK,
                LifecycleState.CANDIDATE,
                "task-b",
                current.copy(allowCrossTask = true),
            ),
        )
    }
}
