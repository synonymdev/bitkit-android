package to.bitkit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

val Brand100 = Color(0xFFFFF1EE)
val Brand500 = Color(0xFFEC5428)
val Teal200 = Color(0xFF03DAC5)

private object ColorPalette {
    @Stable
    val Light = lightColorScheme(
        primary = Brand500,
        primaryContainer = Brand100,
        secondary = Teal200,
        background = Color.White,
        /* // Other default colors to override
        surface = Color.White,
        onPrimary = Color.White,
        onSecondary = Color.Black,
        onBackground = Color.Black,
        onSurface = Color.Black,
        */
    )

    @Stable
    val Dark = darkColorScheme(
        primary = Brand500,
        secondary = Teal200,
    )
}

@Composable
internal fun AppThemeSurface(
    content: @Composable () -> Unit,
) {
    AppTheme {
        Surface(content = content)
    }
}

@Composable
internal fun AppTheme(
    inDarkTheme: Boolean = isSystemInDarkTheme(),
    colorScheme: ColorScheme = if (inDarkTheme) ColorPalette.Dark else ColorPalette.Light,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
