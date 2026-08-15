package relay.ondevice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Utf8PieceDecoderTest {

    @Test
    fun reassemblesSplitMultibyteCharacter() {
        // "中" = E4 B8 AD
        val decoder = Utf8PieceDecoder()
        assertNull(decoder.push(byteArrayOf(0xE4.toByte())))
        assertNull(decoder.push(byteArrayOf(0xB8.toByte())))
        assertEquals("中", decoder.push(byteArrayOf(0xAD.toByte())))
        assertNull(decoder.finish())
    }

    @Test
    fun passesThroughAsciiImmediately() {
        val decoder = Utf8PieceDecoder()
        assertEquals("hi", decoder.push("hi".toByteArray()))
    }
}
