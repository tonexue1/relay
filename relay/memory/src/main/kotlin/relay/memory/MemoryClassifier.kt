package relay.memory

import relay.memory.engine.normalizeText

/**
 * Conservative deterministic defaults. A caller may explicitly provide scope/state on a draft;
 * otherwise only this small durable whitelist becomes confirmed profile memory.
 */
internal object MemoryClassifier {
    private val durableProfilePredicates = setOf(
        "allergic_to",
        "diet",
        "born_in",
        "alumni_of",
        "knows_language",
        "family_of",
        "spouse_of",
        "parent_of",
        "child_of",
        "sibling_of",
        "has_pet",
        "named",
    )

    private val taskPredicates = setOf(
        "plans",
        "has_task",
        "worked_on",
        "has_component",
        "uses_technology",
    )
    private val weakPreferencePredicates = setOf("likes", "dislikes")
    private val strongPreferencePredicates = setOf("prefers")

    data class Classification(
        val scope: MemoryScope,
        val state: MemoryState,
        val scopeId: String,
    )

    fun triple(
        draft: TripleDraft,
        sessionId: String = "",
        taskScopeId: String = "",
        assistantOnly: Boolean = false,
    ): Classification {
        val userSubject = normalizeText(draft.s) in setOf("用户", "user", "我", "本人")
        val defaultScope = when {
            isNovelGraph(draft.graphId) -> MemoryScope.PROFILE
            draft.p in taskPredicates -> MemoryScope.TASK
            userSubject && (
                draft.p in durableProfilePredicates ||
                    draft.p in weakPreferencePredicates ||
                    draft.p in strongPreferencePredicates
                ) -> MemoryScope.PROFILE
            else -> MemoryScope.SESSION
        }
        val scope = draft.scope ?: defaultScope
        val defaultState = when {
            isNovelGraph(draft.graphId) -> MemoryState.CONFIRMED
            assistantOnly -> MemoryState.CANDIDATE
            userSubject && (
                draft.p in durableProfilePredicates || draft.p in strongPreferencePredicates
                ) -> MemoryState.CONFIRMED
            draft.p in weakPreferencePredicates -> MemoryState.CANDIDATE
            else -> MemoryState.CANDIDATE
        }
        val requestedState = draft.state ?: defaultState
        val state = if (assistantOnly && requestedState == MemoryState.CONFIRMED) {
            MemoryState.CANDIDATE
        } else {
            requestedState
        }
        val scopeId = when (scope) {
            MemoryScope.PROFILE -> ""
            MemoryScope.TASK -> draft.scopeId.ifBlank { taskScopeId.ifBlank { sessionId } }
            MemoryScope.SESSION -> draft.scopeId.ifBlank { sessionId }
        }
        return Classification(scope, state, scopeId)
    }

    fun claim(draft: ClaimDraft, sessionId: String, assistantOnly: Boolean): Classification {
        val scope = draft.scope ?: MemoryScope.SESSION
        val requestedState = draft.state ?: MemoryState.CANDIDATE
        val state = if (assistantOnly && requestedState == MemoryState.CONFIRMED) {
            MemoryState.CANDIDATE
        } else {
            requestedState
        }
        val scopeId = when (scope) {
            MemoryScope.PROFILE -> ""
            MemoryScope.TASK, MemoryScope.SESSION -> draft.scopeId.ifBlank { sessionId }
        }
        return Classification(scope, state, scopeId)
    }
}
