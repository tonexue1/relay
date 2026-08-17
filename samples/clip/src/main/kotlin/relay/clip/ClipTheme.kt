package relay.clip

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun ClipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Color(0xFF7CC4FA),
                secondary = Color(0xFFC4B5FD),
                surfaceVariant = Color(0xFF1B2430),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF0B6BCB),
                secondary = Color(0xFF6D28D9),
                surfaceVariant = Color(0xFFEEF2F7),
            )
        },
        content = content,
    )
}
