package relay.orchestra

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AssignmentStatus { running, ok, partial, failed }

data class Assignment(
    val workerId: String,
    val task: String,
    val status: AssignmentStatus,
    val returnSummary: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
)

class TeamBudgetExceeded(val maxWorkers: Int) :
    IllegalStateException("TeamLedger maxWorkers=$maxWorkers exhausted")

/**
 * Structured state for one team run: goal, plan, and recovered assignments.
 *
 * Workers never write [assignments] themselves. Orchestra claims a slot, then
 * records the result on the way back.
 */
class TeamLedger(
    val runId: String,
    val goal: String,
    var plan: String? = null,
    val assignments: MutableList<Assignment> = mutableListOf(),
    val maxWorkers: Int = Int.MAX_VALUE,
) {
    private val lock = Mutex()
    private var claimed = 0

    val dispatchCount: Int get() = claimed

    suspend fun claimWorker() {
        lock.withLock {
            if (claimed >= maxWorkers) throw TeamBudgetExceeded(maxWorkers)
            claimed++
        }
    }

    suspend fun record(assignment: Assignment) {
        lock.withLock { assignments += assignment }
    }
}
