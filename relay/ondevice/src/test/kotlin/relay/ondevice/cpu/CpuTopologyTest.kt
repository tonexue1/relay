package relay.ondevice.cpu

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuTopologyTest {

    @Test
    fun groupsPrimeAndBigCoresButExcludesTheEfficiencyCluster() {
        // Kirin 9020 (Mate 70 Pro): 1x 2.5 GHz + 3x 2.15 GHz + 4x 1.6 GHz.
        val kirin9020 = mapOf(
            0 to 2_500_000L,
            1 to 2_150_000L, 2 to 2_150_000L, 3 to 2_150_000L,
            4 to 1_600_000L, 5 to 1_600_000L, 6 to 1_600_000L, 7 to 1_600_000L,
        )

        assertEquals(
            CpuPlan(threadCount = 4, coreIndices = listOf(0, 1, 2, 3)),
            CpuTopology.plan(kirin9020, coreCount = 8),
        )
    }

    @Test
    fun countsTheWideBigClusterOnSnapdragonStyleLayouts() {
        // Snapdragon 8 Gen 3: 1x 3.3 GHz + 5x 3.2 GHz + 2x 2.3 GHz.
        val sd8Gen3 = mapOf(
            0 to 3_300_000L,
            1 to 3_200_000L, 2 to 3_200_000L, 3 to 3_200_000L, 4 to 3_200_000L, 5 to 3_200_000L,
            6 to 2_300_000L, 7 to 2_300_000L,
        )

        assertEquals(
            CpuPlan(threadCount = 6, coreIndices = listOf(0, 1, 2, 3, 4, 5)),
            CpuTopology.plan(sd8Gen3, coreCount = 8),
        )
    }

    @Test
    fun picksTheFastCoresEvenWhenTheyAreNotTheLowNumberedOnes() {
        // Some vendors number the efficiency cluster first.
        val littleFirst = mapOf(
            0 to 1_800_000L, 1 to 1_800_000L, 2 to 1_800_000L, 3 to 1_800_000L,
            4 to 2_800_000L, 5 to 2_800_000L, 6 to 2_800_000L, 7 to 2_800_000L,
        )

        assertEquals(
            CpuPlan(threadCount = 4, coreIndices = listOf(4, 5, 6, 7)),
            CpuTopology.plan(littleFirst, coreCount = 8),
        )
    }

    @Test
    fun usesEveryCoreWhenAllCoresClockTheSame() {
        val homogeneous = (0 until 4).associateWith { 2_000_000L }

        assertEquals(
            CpuPlan(threadCount = 4, coreIndices = listOf(0, 1, 2, 3)),
            CpuTopology.plan(homogeneous, coreCount = 4),
        )
    }

    @Test
    fun leavesPlacementToTheSchedulerWhenSysfsIsUnreadable() {
        assertEquals(
            CpuPlan(threadCount = 4, coreIndices = emptyList()),
            CpuTopology.plan(emptyMap(), coreCount = 8),
        )
    }

    @Test
    fun leavesPlacementToTheSchedulerWhenOnlyOneCoreReportsAFrequency() {
        assertEquals(
            CpuPlan(threadCount = 3, coreIndices = emptyList()),
            CpuTopology.plan(mapOf(0 to 2_500_000L), coreCount = 6),
        )
    }

    @Test
    fun neverReturnsZeroThreads() {
        assertEquals(1, CpuTopology.plan(emptyMap(), coreCount = 1).threadCount)
        assertEquals(1, CpuTopology.plan(emptyMap(), coreCount = 0).threadCount)
    }

    @Test
    fun neverPinsMoreWorkersThanTheRuntimeExposes() {
        // sysfs can list cores the runtime does not expose (offline, cpuset-restricted).
        val eightListed = (0 until 8).associateWith { 2_000_000L }

        assertEquals(
            CpuPlan(threadCount = 4, coreIndices = listOf(0, 1, 2, 3)),
            CpuTopology.plan(eightListed, coreCount = 4),
        )
    }
}
