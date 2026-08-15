package relay.ondevice.cpu

import java.io.File

/**
 * Which CPUs llama.cpp should run on.
 *
 * [coreIndices] is empty when the topology could not be read, meaning the caller should
 * leave placement to the OS scheduler and only honour [threadCount].
 */
data class CpuPlan(
    val threadCount: Int,
    val coreIndices: List<Int>,
)

/**
 * Derives a [CpuPlan] from the CPU's frequency tiers.
 *
 * Thread placement is not a tuning knob here -- oversubscribing is a performance cliff,
 * not a slope. `ggml_barrier` runs at the end of every operator and is an unbounded spin
 * loop with no sleep path, and on aarch64 its `ggml_thread_cpu_relax` is a bare `yield`,
 * which hints the pipeline without releasing the core to the OS scheduler. So every
 * thread that reaches the barrier early burns 100% CPU until the last one arrives, and
 * any worker the kernel deschedules stalls all the others for a full timeslice. Spread a
 * few hundred operators over each token and that compounds into orders of magnitude.
 *
 * Keeping workers on the performance cores avoids the slow stragglers that trigger it.
 * Measured on a Kirin 9020 (1 prime + 3 big + 4 little): 4 threads beat 8 by ~100x.
 */
object CpuTopology {

    /**
     * Cores within this fraction of the top clock count as performance cores.
     *
     * Modern SoCs split "big" into a prime core plus slightly slower siblings (Kirin
     * 9020: 2.5 GHz + 3x 2.15 GHz + 4x 1.6 GHz). Taking only the top tier would yield
     * a single thread, so the band has to be wide enough to keep the prime and big
     * cores together while still excluding the efficiency cluster.
     */
    private const val PERFORMANCE_BAND = 0.85

    private const val CPU_DIR = "/sys/devices/system/cpu"

    private val cached: CpuPlan by lazy {
        plan(readMaxFrequenciesKHz(), Runtime.getRuntime().availableProcessors())
    }

    /** Plan for the current device. Computed once and reused. */
    fun plan(): CpuPlan = cached

    /** Convenience for callers that only need the thread count (logging, diagnostics). */
    fun recommendedThreadCount(): Int = cached.threadCount

    internal fun plan(freqByCpu: Map<Int, Long>, coreCount: Int): CpuPlan {
        val total = coreCount.coerceAtLeast(1)
        val unpinned = CpuPlan(threadCount = (total / 2).coerceIn(1, total), coreIndices = emptyList())
        if (freqByCpu.size < 2) {
            // No usable topology: assume half the cores are performance cores, which is
            // the common big.LITTLE split and still beats using every core.
            return unpinned
        }
        val threshold = freqByCpu.values.max() * PERFORMANCE_BAND
        val fast = freqByCpu.filterValues { it >= threshold }.keys.sorted().take(total)
        if (fast.isEmpty()) return unpinned
        return CpuPlan(threadCount = fast.size, coreIndices = fast)
    }

    private fun readMaxFrequenciesKHz(): Map<Int, Long> {
        val cpus = File(CPU_DIR).listFiles { file -> file.name.matches(CPU_NAME) }
            ?: return emptyMap()
        return buildMap {
            for (cpu in cpus) {
                val index = cpu.name.removePrefix("cpu").toIntOrNull() ?: continue
                val frequency = readFrequency(File(cpu, "cpufreq/cpuinfo_max_freq"))
                    // Offline or locked-down cores hide cpuinfo_max_freq but often still
                    // expose the governor's ceiling.
                    ?: readFrequency(File(cpu, "cpufreq/scaling_max_freq"))
                    ?: continue
                put(index, frequency)
            }
        }
    }

    private fun readFrequency(file: File): Long? = try {
        file.takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) {
        // sysfs reads fail in plenty of vendor-specific ways; any failure just means
        // this core does not contribute to the topology.
        null
    }

    private val CPU_NAME = Regex("cpu\\d+")
}
