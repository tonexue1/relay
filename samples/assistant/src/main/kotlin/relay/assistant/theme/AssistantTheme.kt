package relay.assistant.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightPaper = Color(0xFFF7F4EE)
private val LightPaperRaised = Color(0xFFFFFDF9)
private val LightInk = Color(0xFF211E1A)
private val LightInkMuted = Color(0xFF6D655D)
private val LightRust = Color(0xFF8B3D2B)
private val LightRustSoft = Color(0xFFF2E2DC)
private val LightMoss = Color(0xFF49624B)

private val LightAssistantColors = lightColorScheme(
    primary = LightRust,
    onPrimary = Color.White,
    primaryContainer = LightRustSoft,
    onPrimaryContainer = Color(0xFF46170D),
    secondary = LightMoss,
    onSecondary = Color.White,
    background = LightPaper,
    onBackground = LightInk,
    surface = LightPaperRaised,
    onSurface = LightInk,
    surfaceVariant = Color(0xFFF0EBE3),
    onSurfaceVariant = LightInkMuted,
    outline = Color(0xFF82786F),
    outlineVariant = Color(0xFFD8D0C5),
    inverseSurface = LightInk,
    inverseOnSurface = Color(0xFFF8F3EC),
    error = Color(0xFFB3261E),
)

private val DarkAssistantColors = darkColorScheme(
    primary = Color(0xFFFFB5A1),
    onPrimary = Color(0xFF561F12),
    primaryContainer = Color(0xFF68291D),
    onPrimaryContainer = Color(0xFFFFDAD0),
    secondary = Color(0xFFB0CFB1),
    onSecondary = Color(0xFF1C361F),
    secondaryContainer = Color(0xFF334D36),
    onSecondaryContainer = Color(0xFFCCEBCD),
    background = Color(0xFF171512),
    onBackground = Color(0xFFECE1D8),
    surface = Color(0xFF201D19),
    onSurface = Color(0xFFECE1D8),
    surfaceVariant = Color(0xFF2C2823),
    onSurfaceVariant = Color(0xFFCFC4BA),
    outline = Color(0xFF958A81),
    outlineVariant = Color(0xFF49433D),
    inverseSurface = Color(0xFFECE1D8),
    inverseOnSurface = Color(0xFF342F2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

internal fun assistantColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkAssistantColors else LightAssistantColors

// Compatibility names are composable semantic roles, not fixed palette colors.
// Existing screens therefore retain their visual vocabulary while following the
// active Material color scheme.
val Paper: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val PaperRaised: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val Ink: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.inverseSurface

val InkMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val Rust: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val RustSoft: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

val Line: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outline

private val AssistantTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

@Composable
fun AssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = assistantColorScheme(darkTheme),
        typography = AssistantTypography,
        content = content,
    )
}
