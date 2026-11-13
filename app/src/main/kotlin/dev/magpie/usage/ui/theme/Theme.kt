package dev.magpie.usage.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Semantic aliases keep charts and custom controls on the same Material palette.
val Page: Color @Composable get() = MaterialTheme.colorScheme.background
val Card: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
val CardBorder: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val TextPrimary: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val TextSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Purple: Color @Composable get() = MaterialTheme.colorScheme.primary
val OnAccent: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
val SoftSurface: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
val Cyan: Color @Composable get() = MaterialTheme.colorScheme.secondary
val Mint: Color @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C995) else Color(0xFF188038)
val Coral: Color @Composable get() = MaterialTheme.colorScheme.error

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    secondaryContainer = Color(0xFFD3E3FD), onSecondaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF006A6A), tertiary = Color(0xFF386A20),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF8F9FC), onSurface = Color(0xFF1F1F1F),
    surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF0F3F9),
    surfaceContainerHigh = Color(0xFFE9EEF6), surfaceContainerHighest = Color(0xFFE1E7EF),
    onSurfaceVariant = Color(0xFF444746), outline = Color(0xFF747775),
    outlineVariant = Color(0xFFDCE1E8), error = Color(0xFFB3261E),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondaryContainer = Color(0xFF0842A0), onSecondaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF80D5D3), tertiary = Color(0xFFA8DAB5),
    background = Color(0xFF111318), onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF111318), onSurface = Color(0xFFE3E3E3),
    surfaceContainerLow = Color(0xFF1B1E24), surfaceContainer = Color(0xFF20242B),
    surfaceContainerHigh = Color(0xFF292D34), surfaceContainerHighest = Color(0xFF343840),
    onSurfaceVariant = Color(0xFFC4C7C5), outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF3D424A), error = Color(0xFFF2B8B5),
)

@Composable
fun MagpieTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.let { activity ->
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
