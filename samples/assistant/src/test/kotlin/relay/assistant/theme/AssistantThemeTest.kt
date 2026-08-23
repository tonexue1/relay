package relay.assistant.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantThemeTest {
    @Test
    fun lightAndDarkSchemesKeepSemanticContentReadable() {
        listOf(false, true).forEach { darkTheme ->
            val colors = assistantColorScheme(darkTheme)

            assertContrast(colors.onBackground, colors.background, 4.5f)
            assertContrast(colors.onSurface, colors.surface, 4.5f)
            assertContrast(colors.onSurfaceVariant, colors.surfaceVariant, 4.5f)
            assertContrast(colors.onPrimary, colors.primary, 4.5f)
            assertContrast(colors.onPrimaryContainer, colors.primaryContainer, 4.5f)
            assertContrast(colors.inverseOnSurface, colors.inverseSurface, 4.5f)
            assertContrast(colors.outline, colors.background, 3f)
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("Expected contrast >= $minimum, was $ratio", ratio >= minimum)
    }
}
