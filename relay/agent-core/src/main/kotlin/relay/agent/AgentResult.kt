package relay.agent

import relay.llm.model.FinishReason
import relay.llm.model.Message

data class AgentResult(
    val messages: List<Message>,
    val text: String? = null,
    val finishReason: FinishReason? = null,
)
