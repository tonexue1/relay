package relay.ondevice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGenerateCodecTest {

    @Test
    fun unpacksTokenCountsAndConvertsNanosecondsToMillis() {
        val result = NativeGenerateCodec.unpack(
            packed = (12 shl 16) or 40,
            timingsNs = longArrayOf(
                120_400_000L,
                135_900_000L,
                800_100_000L,
            ),
        )

        val ok = result as GenerateResult.Ok
        assertEquals(12, ok.promptTokens)
        assertEquals(40, ok.completionTokens)
        assertEquals(GenerateTimings(prefillMs = 120, ttftMs = 135, decodeMs = 800), ok.timings)
    }

    @Test
    fun leavesTimingsUnsetWhenTheArrayIsMissingOrShort() {
        val noArray = NativeGenerateCodec.unpack(packed = 1 shl 16 or 1, timingsNs = null)
        assertNull((noArray as GenerateResult.Ok).timings)

        val short = NativeGenerateCodec.unpack(packed = 1 shl 16 or 1, timingsNs = longArrayOf(1L, 2L))
        assertNull((short as GenerateResult.Ok).timings)
    }

    @Test
    fun mapsCancelAndFailureCodesBeforeReadingTimings() {
        assertEquals(
            GenerateResult.Cancelled,
            NativeGenerateCodec.unpack(packed = -100, timingsNs = longArrayOf(1, 2, 3)),
        )

        val failed = NativeGenerateCodec.unpack(packed = -6, timingsNs = longArrayOf(1, 2, 3))
        assertTrue(failed is GenerateResult.Failed)
        assertEquals(-6, (failed as GenerateResult.Failed).code)
    }
}
