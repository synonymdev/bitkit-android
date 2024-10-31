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

val Gray100 = Color(0xFFF4F4F4)
val Gray200 = Color(0xFFE0E0E0)
val Gray300 = Color(0xFFBDBDBD)
val Gray400 = Color(0xFFABABAB)
val gray900 = Color(0xFF212121)
val Brand50 = Color(0xFFFFF1EE)
val Brand500 = Color(0xFFEC5428)
val Teal200 = Color(0xFF03DAC5)
val Green500 = Color(0xFF4CAF50)
val Orange500 = Color(0xFFFF9800)
val Purple500 = Color(0xFF9C27B0)

private object ColorPalette {
    @Stable
    val Light = lightColorScheme(
        primary = Brand500,
        primaryContainer = Brand50,
        secondary = Teal200,
        background = Color.White,
        surface = Color.White,
        surfaceVariant = Gray100,
        outline = Gray300,
        outlineVariant = Gray400,
        // Other default colors to override
        /*
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
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = gray900,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
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
private fun AppTheme(
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
