package relay.agent

import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import relay.llm.Provider
import relay.llm.RelayLlmException
import relay.llm.model.Capability
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.FinishReason
import relay.llm.model.Message
import relay.llm.model.Role
import relay.llm.model.ToolCall
import relay.llm.token.HeuristicTokenCounter
import relay.llm.token.TokenCounter
import relay.llm.tool.ToolCallAccumulator

/**
 * Stateful agent loop over a [Provider].
 *
 * Working memory is [state.messages]. Each [prompt] / [continueRun] emits the pi-style
 * lifecycle and dispatches tools until the model stops or [AgentConfig.maxTurns] is hit.
 * A tool call past that budget is not executed: it receives [TOOL_BUDGET_EXHAUSTED] and
 * the next LLM call is forced to write (no tools). The agent is not itself a [Provider]
 * -- wrapping it as one would nest loops.
 */
class Agent(
    private val provider: Provider,
    config: AgentConfig,
    tools: List<Tool> = emptyList(),
    transformContext: (suspend (List<Message>) -> List<Message>)? = null,
    private val beforeToolCall: suspend (ToolCall) -> BeforeToolCallResult? = { null },
    private val tokenCounter: TokenCounter = HeuristicTokenCounter(),
    private val contextAugmenters: List<ContextAugmenter> = emptyList(),
) {
    val state: AgentState = AgentState(
        systemPrompt = config.systemPrompt,
        model = config.model,
        tools = tools,
    )

    private val maxTurns: Int = config.maxTurns.coerceAtLeast(1)
    private val toolExecution: ToolExecutionMode = config.toolExecution
    private val temperature: Double? = config.temperature
    private val maxTokens: Int? = config.maxTokens
    private val timeoutMillis: Long? = config.timeoutMillis

    private val transformContext: suspend (List<Message>) -> List<Message> =
        transformContext ?: { it }

    fun prompt(input: String): Flow<AgentEvent> = flow {
        val user = Message.user(input)
        append(user)
        emitAllRun(startingUser = user)
    }

    /**
     * Resume from the current transcript without appending a user turn.
     *
     * The last message must be `user` or `tool`, matching pi's `continue()`.
     */
    fun continueRun(): Flow<AgentEvent> = flow {
        val last = state.messages.lastOrNull()
            ?: throw AgentException.CannotContinue("transcript is empty")
        if (last.role != Role.USER && last.role != Role.TOOL) {
            throw AgentException.CannotContinue(
                "continue requires last message to be user or tool, was ${last.role}",
            )
        }
        emitAllRun(startingUser = null)
    }

    suspend fun run(input: String): AgentResult {
        var text: String? = null
        var finishReason: FinishReason? = null
        var endMessages: List<Message> = emptyList()
        prompt(input).collect { event ->
            when (event) {
                is AgentEvent.MessageEnd ->
                    if (event.message.role == Role.ASSISTANT) text = event.message.content
                is AgentEvent.MessageUpdate ->
                    if (event.chunk is ChatChunk.Done) finishReason = event.chunk.finishReason
                is AgentEvent.AgentEnd -> endMessages = event.messages
                else -> Unit
            }
        }
        return AgentResult(
            messages = endMessages.ifEmpty { state.messages },
            text = text,
            finishReason = finishReason,
        )
    }

    private suspend fun assembleRequestMessages(): List<Message> {
        val additions = buildList {
            for (augmenter in contextAugmenters) {
                addAll(augmenter.augment(state.messages).messages)
            }
        }
        val projected = transformContext(state.messages)
        return withSystem(additions + trimTranscript(projected, additions))
    }

    private fun trimTranscript(messages: List<Message>, additions: List<Message>): List<Message> {
        val info = provider.info.model(state.model)
        return WindowTrim(
            contextWindow = info?.contextWindow ?: Int.MAX_VALUE,
            reserveOutputTokens = maxTokens ?: info?.maxOutputTokens ?: 0,
            tokenCounter = tokenCounter,
            model = state.model,
            extraTokens = {
                reservedTokens() + tokenCounter.count(additions, state.model)
            },
        )(messages)
    }

    private fun reservedTokens(): Int {
        val sys = state.systemPrompt
        val systemTokens = if (sys.isBlank()) {
            0
        } else {
            tokenCounter.count(listOf(Message.system(sys)), state.model)
        }
        return systemTokens + tokenCounter.countTools(state.tools.map { it.def }, state.model)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.emitAllRun(
        startingUser: Message?,
    ) {
        if (state.isRunning) throw AgentException.AlreadyRunning()
        rejectToolsIfUnsupported()
        state.isRunning = true
        try {
            emit(AgentEvent.AgentStart)
            var emittedOpeningUser = false
            var toolBatches = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                emit(AgentEvent.TurnStart)

                if (startingUser != null && !emittedOpeningUser) {
                    emit(AgentEvent.MessageStart(startingUser))
                    emit(AgentEvent.MessageEnd(startingUser))
                    emittedOpeningUser = true
                }

                val folded = collectAndCommitAssistant(withTools = true)
                if (folded.message.toolCalls.isEmpty()) {
                    emit(AgentEvent.TurnEnd(folded.message, emptyList()))
                    emit(AgentEvent.AgentEnd(state.messages))
                    return
                }

                if (toolBatches >= maxTurns) {
                    val refused = refuseToolBatch(folded.message.toolCalls)
                    emit(AgentEvent.TurnEnd(folded.message, refused))
                    emit(AgentEvent.TurnStart)
                    val summary = collectAndCommitAssistant(withTools = false)
                    emit(AgentEvent.TurnEnd(summary.message, emptyList()))
                    emit(AgentEvent.AgentEnd(state.messages))
                    return
                }

                val toolResults = executeToolBatch(folded.message.toolCalls)
                for (result in toolResults) {
                    append(result)
                    emit(AgentEvent.MessageStart(result))
                    emit(AgentEvent.MessageEnd(result))
                }
                emit(AgentEvent.TurnEnd(folded.message, toolResults))
                toolBatches++
            }
        } finally {
            state.isRunning = false
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.collectAndCommitAssistant(
        withTools: Boolean,
    ): FoldedAssistant {
        val request = ChatRequest(
            model = state.model,
            messages = assembleRequestMessages(),
            tools = if (withTools) state.tools.map { it.def } else emptyList(),
            temperature = temperature,
            maxTokens = maxTokens,
            timeoutMillis = timeoutMillis,
        )
        val assistantStart = Message(role = Role.ASSISTANT)
        emit(AgentEvent.MessageStart(assistantStart))
        val folded = collectAssistant(request)
        val message = if (!withTools && folded.message.toolCalls.isNotEmpty()) {
            folded.message.copy(toolCalls = emptyList())
        } else {
            folded.message
        }
        append(message)
        emit(AgentEvent.MessageEnd(message))
        return folded.copy(message = message)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.refuseToolBatch(
        calls: List<ToolCall>,
    ): List<Message> {
        val result = TOOL_BUDGET_EXHAUSTED.format(maxTurns)
        return calls.map { call ->
            emit(AgentEvent.ToolExecutionStart(call))
            emit(AgentEvent.ToolExecutionEnd(call, result, isError = false))
            val message = Message.toolResult(toolCallId = call.id, content = result)
            append(message)
            emit(AgentEvent.MessageStart(message))
            emit(AgentEvent.MessageEnd(message))
            message
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.collectAssistant(
        request: ChatRequest,
    ): FoldedAssistant {
        val text = StringBuilder()
        val toolCalls = ToolCallAccumulator()
        var finishReason = FinishReason.UNKNOWN
        provider.stream(request).collect { chunk ->
            currentCoroutineContext().ensureActive()
            emit(AgentEvent.MessageUpdate(chunk))
            when (chunk) {
                is ChatChunk.Text -> text.append(chunk.delta)
                is ChatChunk.ToolCalls -> toolCalls.accept(chunk.delta)
                is ChatChunk.Done -> finishReason = chunk.finishReason
            }
        }
        return FoldedAssistant(
            message = Message(
                role = Role.ASSISTANT,
                content = text.toString().takeIf { it.isNotEmpty() },
                toolCalls = toolCalls.build(),
            ),
            finishReason = finishReason,
        )
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.executeToolBatch(
        calls: List<ToolCall>,
    ): List<Message> {
        val outcomes = arrayOfNulls<ToolOutcome>(calls.size)
        val pending = mutableListOf<PendingCall>()

        for ((index, call) in calls.withIndex()) {
            currentCoroutineContext().ensureActive()
            emit(AgentEvent.ToolExecutionStart(call))
            val tool = state.tools.firstOrNull { it.def.name == call.name }
            if (tool == null) {
                outcomes[index] = ToolOutcome("Unknown tool '${call.name}'", isError = true)
                emit(AgentEvent.ToolExecutionEnd(call, outcomes[index]!!.result, isError = true))
                continue
            }
            val blocked = beforeToolCall(call)
            if (blocked?.block == true) {
                val reason = blocked.reason ?: "Tool '${call.name}' was blocked"
                outcomes[index] = ToolOutcome(reason, isError = true)
                emit(AgentEvent.ToolExecutionEnd(call, reason, isError = true))
                continue
            }
            pending += PendingCall(index, call, tool)
        }

        val sequential = toolExecution == ToolExecutionMode.Sequential ||
            pending.any { it.tool.executionMode == ToolExecutionMode.Sequential }

        if (sequential) {
            for (item in pending) {
                currentCoroutineContext().ensureActive()
                val outcome = runTool(item.tool, item.call)
                outcomes[item.index] = outcome
                emit(AgentEvent.ToolExecutionEnd(item.call, outcome.result, outcome.isError))
            }
        } else {
            val completed = Collections.synchronizedList(mutableListOf<Pair<PendingCall, ToolOutcome>>())
            coroutineScope {
                for (item in pending) {
                    launch {
                        val outcome = runTool(item.tool, item.call)
                        completed += item to outcome
                    }
                }
            }
            for ((item, outcome) in completed) {
                outcomes[item.index] = outcome
                emit(AgentEvent.ToolExecutionEnd(item.call, outcome.result, outcome.isError))
            }
        }

        return calls.mapIndexed { index, call ->
            val outcome = outcomes[index] ?: ToolOutcome("Tool produced no result", isError = true)
            Message.toolResult(toolCallId = call.id, content = outcome.result)
        }
    }

    private suspend fun runTool(tool: Tool, call: ToolCall): ToolOutcome =
        try {
            coroutineContext.ensureActive()
            ToolOutcome(tool.execute(call.id, call.argumentsJson), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome(e.message ?: e.toString(), isError = true)
        }

    private fun rejectToolsIfUnsupported() {
        if (state.tools.isEmpty()) return
        if (provider.info.supports(state.model, Capability.TOOLS)) return
        throw RelayLlmException.InvalidRequest(
            message = "Model '${state.model}' does not support tools",
            providerId = provider.info.id,
        )
    }

    private fun withSystem(messages: List<Message>): List<Message> {
        val withoutSystem = messages.filter { it.role != Role.SYSTEM }
        val sys = state.systemPrompt
        if (sys.isBlank()) return withoutSystem
        return listOf(Message.system(sys)) + withoutSystem
    }

    private fun append(message: Message) {
        state.messages = state.messages + message
    }

    private data class FoldedAssistant(
        val message: Message,
        val finishReason: FinishReason,
    )

    private data class ToolOutcome(val result: String, val isError: Boolean)

    private data class PendingCall(val index: Int, val call: ToolCall, val tool: Tool)
}

internal const val TOOL_BUDGET_EXHAUSTED =
    "Tool budget exhausted (maxTurns=%d). This tool was not executed. Summarize your findings now. Do not call more tools."
