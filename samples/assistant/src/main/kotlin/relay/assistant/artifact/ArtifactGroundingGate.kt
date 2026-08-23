package relay.assistant.artifact

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import relay.agent.BeforeToolCallResult
import relay.llm.model.ToolCall
import relay.uikit.UiToolNames

object ArtifactGroundingGate {
    fun check(call: ToolCall, evidence: String): BeforeToolCallResult? {
        if (call.name != UiToolNames.WRITE_MARKDOWN && call.name != UiToolNames.REVISE_ARTIFACT) return null
        val body = runCatching {
            Json.parseToJsonElement(call.argumentsJson).jsonObject["body"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
        if (body.isBlank()) return null
        val evidenceNumbers = extractNumbers(evidence)
        if (evidenceNumbers.isEmpty()) return null
        val allowed = deriveNumbers(evidenceNumbers)
        val suspicious = extractTokens(body).filter { token ->
            val value = token.toDoubleOrNull() ?: return@filter false
            !isStructuralNumber(value) && allowed.none { abs(it - value) < 0.0001 }
        }.distinct()
        if (suspicious.isEmpty()) return null
        return BeforeToolCallResult(
            block = true,
            reason = "Markdown 中出现未被用户原文或记忆数据支持的数值：${suspicious.joinToString()}。" +
                "请删除这些数值，或只保留能从原始数据直接计算并明确说明公式的结果，然后重试。",
        )
    }

    private fun extractNumbers(text: String): Set<Double> =
        extractTokens(text).mapNotNull { it.toDoubleOrNull() }.toSet()

    private fun extractTokens(text: String): List<String> =
        NUMBER.findAll(text).map { it.value.removeSuffix("%") }.toList()

    private fun deriveNumbers(source: Set<Double>): Set<Double> {
        val values = source.toMutableSet()
        repeat(2) {
            val snapshot = values.filter { abs(it) < MAX_DERIVED_VALUE }.take(MAX_SOURCE_NUMBERS)
            for (left in snapshot) {
                for (right in snapshot) {
                    values += left + right
                    values += left - right
                    values += left * right
                    if (abs(right) > 0.0001) values += left / right
                }
            }
        }
        return values.filterTo(mutableSetOf()) { it.isFinite() && abs(it) < MAX_DERIVED_VALUE }
    }

    private fun isStructuralNumber(value: Double): Boolean {
        val integer = value % 1.0 == 0.0
        return (integer && value in 0.0..31.0) || (integer && value in 1900.0..2100.0)
    }

    private val NUMBER = Regex("""(?<![\p{L}\p{N}])[-+]?\d+(?:\.\d+)?%?""")
    private const val MAX_SOURCE_NUMBERS = 40
    private const val MAX_DERIVED_VALUE = 1_000_000.0
}
