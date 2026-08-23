package relay.assistant.state

import relay.memory.MemoryScope
import relay.memory.MemoryState
import relay.memory.RecallContext

internal object MemoryVisibility {
    fun recallable(
        scope: MemoryScope,
        state: MemoryState,
        scopeId: String,
        context: RecallContext,
    ): Boolean = when (scope) {
        MemoryScope.PROFILE -> state == MemoryState.CONFIRMED
        MemoryScope.TASK -> context.allowCrossTask ||
            (scopeId.isNotBlank() && scopeId == context.taskScopeId)
        MemoryScope.SESSION -> scopeId.isNotBlank() && scopeId == context.sessionId
    }

    fun isolated(scope: MemoryScope, scopeId: String, context: RecallContext): Boolean =
        when (scope) {
            MemoryScope.PROFILE -> false
            MemoryScope.TASK -> scopeId.isNotBlank() && scopeId != context.taskScopeId
            MemoryScope.SESSION -> scopeId.isBlank() || scopeId == "legacy" || scopeId != context.sessionId
        }

    fun label(scope: MemoryScope, state: MemoryState, scopeId: String): String {
        val bucket = when {
            scope == MemoryScope.PROFILE && state == MemoryState.CONFIRMED -> "资料"
            scope == MemoryScope.PROFILE -> "资料候选"
            scopeId == "legacy" -> "历史隔离"
            scope == MemoryScope.TASK -> "任务"
            else -> "会话"
        }
        val readiness = if (state == MemoryState.CONFIRMED) "已确认" else "候选"
        return "$bucket · $readiness"
    }
}
