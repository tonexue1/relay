package relay.assistant.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantUiStateTest {

    @Test
    fun `on-device mode sends without an API key only after its model loads`() {
        val unloaded = AssistantUiState(
            inferenceMode = InferenceMode.ON_DEVICE,
            onDeviceModelLoaded = false,
        )
        val loaded = unloaded.copy(onDeviceModelLoaded = true)

        assertFalse(unloaded.canSend)
        assertTrue(loaded.canSend)
    }

    @Test
    fun `on-device mode waits for tool-call evaluation to finish before sending`() {
        val evaluating = AssistantUiState(
            inferenceMode = InferenceMode.ON_DEVICE,
            onDeviceModelLoaded = true,
            onDeviceEvaluating = true,
        )

        assertFalse(evaluating.canSend)
    }
}
