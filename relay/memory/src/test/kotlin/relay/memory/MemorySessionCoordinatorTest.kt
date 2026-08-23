package relay.memory

import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemorySessionCoordinatorTest {

    @Test
    fun fourthTurnFlushesOnceAndCancelsIdle() = runTest {
        val reasons = mutableListOf<FlushReason>()
        val coordinator = MemorySessionCoordinator(this, turnThreshold = 4, idleMillis = 60_000) {
            reasons += it
        }

        repeat(3) { coordinator.onTurnCompleted() }
        runCurrent()
        assertEquals(emptyList(), reasons)
        coordinator.onTurnCompleted()
        runCurrent()
        assertEquals(listOf(FlushReason.TURN_THRESHOLD), reasons)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, reasons.size)
    }

    @Test
    fun idleAndForcedFlushUseTheirReasons() = runTest {
        val reasons = mutableListOf<FlushReason>()
        val coordinator = MemorySessionCoordinator(this, turnThreshold = 4, idleMillis = 60_000) {
            reasons += it
        }

        coordinator.onTurnCompleted()
        advanceTimeBy(60_000)
        runCurrent()
        coordinator.flushAndJoin(FlushReason.BACKGROUND)

        assertEquals(listOf(FlushReason.IDLE, FlushReason.BACKGROUND), reasons)
    }
}
