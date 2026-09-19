package relay.ondevice

import kotlinx.coroutines.flow.collect
import relay.llm.Provider
import relay.llm.model.ChatChunk
import relay.llm.model.ChatRequest
import relay.llm.model.Message
import relay.llm.model.ToolDef

/** Runs deterministic tool-call cases against a loaded provider. */
object ToolCallEvaluation {

    data class Case(
        val id: String,
        val prompt: String,
        val expectedToolName: String,
    )

    data class Failure(
        val case: Case,
        val actualToolNames: List<String>,
    )

    data class Result(
        val total: Int,
        val successes: Int,
        val failures: List<Failure>,
    ) {
        val successRate: Float
            get() = if (total == 0) 0f else successes.toFloat() / total
    }

    val defaultCases: List<Case> = listOf(
        Case("time_now", "现在几点？", "get_current_time"),
        Case("time_current", "当前时间是多少", "get_current_time"),
        Case("time_local", "告诉我本地时间", "get_current_time"),
        Case("date_today", "今天是几号？", "get_current_time"),
        Case("weekday_today", "今天星期几", "get_current_time"),
        Case("date_tomorrow", "明天是几月几日？", "get_current_time"),
        Case("weekday_tomorrow", "明天周几？", "get_current_time"),
        Case("date_yesterday", "昨天是星期几？", "get_current_time"),
        Case("relative_next_week", "下周一是几号？", "get_current_time"),
        Case("relative_last_week", "上周五是几号？", "get_current_time"),
        Case("clock_24_hour", "用 24 小时制说一下现在时间", "get_current_time"),
        Case("clock_minutes", "当前几点几分", "get_current_time"),
        Case("day_of_month", "这个月今天是第几天", "get_current_time"),
        Case("month", "现在是几月", "get_current_time"),
        Case("year", "今年是哪一年", "get_current_time"),
        Case("time_zone", "我手机当前时区的时间是什么", "get_current_time"),
        Case("date_format", "把今天日期按 yyyy-MM-dd 告诉我", "get_current_time"),
        Case("morning", "现在算上午还是下午？", "get_current_time"),
        Case("week_number", "今天是今年第几周？", "get_current_time"),
        Case("relative_day", "后天是星期几？", "get_current_time"),
    )

    suspend fun run(
        provider: Provider,
        model: String,
        tools: List<ToolDef>,
        cases: List<Case> = defaultCases,
    ): Result {
        val failures = mutableListOf<Failure>()
        cases.forEach { case ->
            val toolNames = mutableListOf<String>()
            provider.stream(
                ChatRequest(
                    model = model,
                    messages = listOf(Message.user(case.prompt)),
                    tools = tools,
                    temperature = 0.0,
                    maxTokens = 96,
                ),
            ).collect { chunk ->
                if (chunk is ChatChunk.ToolCalls) chunk.delta.name?.let(toolNames::add)
            }
            if (case.expectedToolName !in toolNames) failures += Failure(case, toolNames)
        }
        return Result(
            total = cases.size,
            successes = cases.size - failures.size,
            failures = failures,
        )
    }
}
