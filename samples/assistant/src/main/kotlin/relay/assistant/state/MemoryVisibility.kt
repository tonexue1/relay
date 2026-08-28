package relay.assistant.state

import relay.memory.MemoryScope
import relay.memory.RecallContext
import relay.memory.api.LifecycleState

internal object MemoryVisibility {
    fun recallable(
        scope: MemoryScope,
        lifecycle: LifecycleState,
        scopeId: String,
        context: RecallContext,
    ): Boolean {
        if (lifecycle == LifecycleState.RETRACTED) return false
        return when (scope) {
            MemoryScope.PROFILE -> lifecycle == LifecycleState.ACTIVE
            MemoryScope.TASK -> context.allowCrossTask ||
                (scopeId.isNotBlank() && scopeId == context.taskScopeId)
            MemoryScope.SESSION -> scopeId.isNotBlank() && scopeId == context.sessionId
        }
    }

    fun isolated(scope: MemoryScope, scopeId: String, context: RecallContext): Boolean =
        when (scope) {
            MemoryScope.PROFILE -> false
            MemoryScope.TASK -> scopeId.isNotBlank() && scopeId != context.taskScopeId
            MemoryScope.SESSION -> scopeId.isBlank() || scopeId == "legacy" || scopeId != context.sessionId
        }

    fun label(scope: MemoryScope, lifecycle: LifecycleState, scopeId: String): String {
        val bucket = when {
            scope == MemoryScope.PROFILE && lifecycle == LifecycleState.ACTIVE -> "资料"
            scope == MemoryScope.PROFILE -> "资料候选"
            scopeId == "legacy" -> "历史隔离"
            scope == MemoryScope.TASK -> "任务"
            else -> "会话"
        }
        val readiness = when (lifecycle) {
            LifecycleState.ACTIVE -> "已确认"
            LifecycleState.CANDIDATE -> "候选"
            LifecycleState.RETRACTED -> "已撤回"
        }
        return "$bucket · $readiness"
    }
}
