package relay.assistant.session

import android.content.Context
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import relay.llm.model.Message
import relay.uikit.ChatTurn
import relay.uikit.OrderedTurnReducer
import relay.uikit.TurnItem
import relay.uikit.ChoiceFormSpec

@Serializable
data class AssistantSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turns: List<ChatTurn> = emptyList(),
    val researchEntity: String? = null,
    val memoryScopeId: String = "",
) {
    val effectiveMemoryScopeId: String
        get() = memoryScopeId.ifBlank { id }

    val summary: String
        get() = turns.asReversed()
            .flatMap { it.items.asReversed() }
            .filterIsInstance<relay.uikit.TurnItem.Text>()
            .firstOrNull()
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.take(42)
            .orEmpty()
}

internal fun List<ChatTurn>.toAgentTranscript(): List<Message> = buildList {
    this@toAgentTranscript.forEach { turn ->
        when (turn.role) {
            "user" -> {
                val text = turn.items
                    .filterIsInstance<TurnItem.Text>()
                    .map { it.text }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (text.isNotBlank()) add(Message.user(text))
            }
            "assistant" -> {
                val visible = OrderedTurnReducer.visibleProjection(turn)
                if (visible.isNotBlank()) add(Message.assistant(visible))
                turn.items.filterIsInstance<TurnItem.Widget>()
                    .mapNotNull { it.spec as? ChoiceFormSpec }
                    .filter { it.submittedAnswers != null }
                    .forEach { add(Message.user(it.submittedTranscript())) }
            }
        }
    }
}

private fun ChoiceFormSpec.submittedTranscript(): String = buildString {
    appendLine("已完成选择表单「$title」。")
    if (taskAnchor.isNotBlank()) appendLine("原始任务：$taskAnchor")
    questions.forEach { question ->
        val labels = submittedAnswers.orEmpty()[question.id].orEmpty().mapNotNull { answerId ->
            question.options.firstOrNull { it.id == answerId }?.label
        }
        if (labels.isNotEmpty()) appendLine("- ${question.title}：${labels.joinToString("、")}")
    }
}

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("assistant-sessions", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "itemType"
    }

    fun load(): List<AssistantSession> {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AssistantSession>>(raw) }.getOrDefault(emptyList())
    }

    fun save(sessions: List<AssistantSession>) {
        preferences.edit().putString(KEY_SESSIONS, json.encodeToString(sessions)).apply()
    }

    fun loadApiKey(defaultValue: String): String =
        preferences.getString(KEY_API_KEY, null) ?: defaultValue

    fun saveApiKey(value: String) {
        preferences.edit().putString(KEY_API_KEY, value).apply()
    }

    fun loadMemoryEnabled(): Boolean = preferences.getBoolean(KEY_MEMORY_ENABLED, true)

    fun saveMemoryEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()
    }

    companion object {
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_API_KEY = "api-key"
        private const val KEY_MEMORY_ENABLED = "memory-enabled"

        fun fresh(now: Long = System.currentTimeMillis()): AssistantSession {
            val id = UUID.randomUUID().toString()
            return AssistantSession(
                id = id,
                title = "新对话",
                updatedAt = now,
                memoryScopeId = id,
            )
        }

        fun research(entity: String, now: Long = System.currentTimeMillis()): AssistantSession {
            val normalized = entity.trim().take(40)
            val id = UUID.randomUUID().toString()
            return AssistantSession(
                id = id,
                title = "研究 · $normalized",
                updatedAt = now,
                researchEntity = normalized,
                memoryScopeId = "research:${normalized.lowercase()}",
                turns = listOf(
                    ChatTurn(
                        id = "$id-research",
                        role = "assistant",
                        items = listOf(
                            TurnItem.Text(
                                id = "$id-research-text",
                                text = "已为「$normalized」开启扩展研究。你想先研究哪个方面？",
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}
