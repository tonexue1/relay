package relay.orchestra

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TeamLedgerTest {

    @Test
    fun recordAppendsAssignmentsInOrder() = runTest {
        val ledger = TeamLedger(runId = "r1", goal = "research")
        ledger.claimWorker()
        ledger.record(Assignment("scout", "find A", AssignmentStatus.ok, "A"))
        ledger.claimWorker()
        ledger.record(Assignment("numbers", "count", AssignmentStatus.ok, "3"))

        assertEquals(2, ledger.dispatchCount)
        assertEquals(listOf("scout", "numbers"), ledger.assignments.map { it.workerId })
        assertEquals("research", ledger.goal)
    }

    @Test
    fun claimWorkerThrowsWhenBudgetExhausted() = runTest {
        val ledger = TeamLedger(runId = "r1", goal = "cap", maxWorkers = 1)
        ledger.claimWorker()
        assertFailsWith<TeamBudgetExceeded> { ledger.claimWorker() }
        assertEquals(1, ledger.dispatchCount)
    }
}
