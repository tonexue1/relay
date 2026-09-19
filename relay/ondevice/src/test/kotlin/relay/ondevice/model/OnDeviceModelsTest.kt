package relay.ondevice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceModelsTest {

    @Test
    fun `selectable models are complete downloadable checkpoints`() {
        val models = OnDeviceModels.selectable

        assertEquals(
            listOf("qwen2.5-0.5b-instruct", "qwen2.5-3b-instruct"),
            models.map(ModelSpec::id),
        )
        assertTrue(models.all { it.sha256.length == 64 })
        assertTrue(models.all { it.expectedBytes > 0 })
        assertTrue(models.all { it.downloadUrl.startsWith("https://www.modelscope.cn/models/Qwen/") })
        assertEquals(listOf(491_400_032L, 2_104_932_768L), models.map(ModelSpec::expectedBytes))
    }
}
