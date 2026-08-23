package relay.assistant.ui

import relay.uikit.ProcessStatus
import relay.uikit.TurnItem
import relay.uikit.UiToolNames

internal enum class ProcessDisplay {
    HIDDEN,
    AGGREGATED,
    ERROR,
}

internal fun processDisplay(process: TurnItem.Process): ProcessDisplay {
    if (process.status == ProcessStatus.FAILED) return ProcessDisplay.ERROR
    val presentationOnly = process.label in UiToolNames.renderers ||
        process.label in UiToolNames.writers ||
        process.label == UiToolNames.READ_ARTIFACT ||
        process.label == UiToolNames.REVISE_ARTIFACT
    return if (presentationOnly) ProcessDisplay.HIDDEN else ProcessDisplay.AGGREGATED
}

internal fun processSummary(processes: List<TurnItem.Process>): String {
    val aggregated = processes.filter { processDisplay(it) == ProcessDisplay.AGGREGATED }
    val memoryCount = aggregated.count { it.label.startsWith("memory_") }
    val otherCount = aggregated.size - memoryCount
    return when {
        memoryCount > 0 && otherCount > 0 -> "参考了记忆 · 另执行 $otherCount 项操作"
        memoryCount > 0 -> "参考了记忆 · $memoryCount 次查询"
        otherCount > 0 -> "执行了 $otherCount 项操作"
        else -> ""
    }
}
