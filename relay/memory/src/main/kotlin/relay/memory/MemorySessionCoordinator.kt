package relay.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class FlushReason {
    TURN_THRESHOLD,
    IDLE,
    RESET,
    NEW_SESSION,
    BACKGROUND,
    FOREGROUND_RECOVERY,
}

/**
 * Host-side scheduling policy. It owns no database or Android lifecycle.
 */
class MemorySessionCoordinator(
    private val scope: CoroutineScope,
    private val turnThreshold: Int = 4,
    private val idleMillis: Long = 60_000,
    private val onFlush: suspend (FlushReason) -> Unit,
) {
    private var completedUserTurns = 0
    private var idleJob: Job? = null
    private var flushJob: Job? = null
    private var rerunReason: FlushReason? = null

    init {
        require(turnThreshold > 0)
        require(idleMillis > 0)
    }

    fun onTurnCompleted() {
        completedUserTurns++
        idleJob?.cancel()
        if (completedUserTurns >= turnThreshold) {
            requestFlush(FlushReason.TURN_THRESHOLD)
        } else {
            idleJob = scope.launch {
                delay(idleMillis)
                requestFlush(FlushReason.IDLE)
            }
        }
    }

    fun requestFlush(reason: FlushReason): Job {
        idleJob?.cancel()
        val active = flushJob
        if (active?.isActive == true) {
            rerunReason = stronger(rerunReason, reason)
            return active
        }
        return scope.launch {
            var next: FlushReason? = reason
            while (next != null) {
                onFlush(next)
                completedUserTurns = 0
                next = rerunReason
                rerunReason = null
            }
        }.also { flushJob = it }
    }

    suspend fun flushAndJoin(reason: FlushReason) {
        requestFlush(reason).join()
        val rerun = flushJob
        if (rerun?.isActive == true) rerun.join()
    }

    fun cancel() {
        idleJob?.cancel()
        flushJob?.cancel()
        rerunReason = null
    }

    private fun stronger(current: FlushReason?, incoming: FlushReason): FlushReason {
        if (current == null) return incoming
        return if (priority(incoming) > priority(current)) incoming else current
    }

    private fun priority(reason: FlushReason): Int = when (reason) {
        FlushReason.RESET,
        FlushReason.NEW_SESSION,
        FlushReason.BACKGROUND,
        FlushReason.FOREGROUND_RECOVERY,
        -> 2
        FlushReason.TURN_THRESHOLD,
        FlushReason.IDLE,
        -> 1
    }
}
