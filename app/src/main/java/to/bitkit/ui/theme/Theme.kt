package to.bitkit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val Gray100 = Color(0xFFF4F4F4)
val Gray900 = Color(0xFF212121)

private object ColorPalette {
    @Stable
    val Light = lightColorScheme(
        primary = Colors.White,
        secondary = Colors.White64,
        background = Colors.Black,
        surface = Color.White,
        surfaceVariant = Gray100,
        outline = Gray100,
        outlineVariant = Gray100, // divider default
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
        primary = Colors.White,
        secondary = Colors.White64,
        background = Colors.Black,
        surface = Colors.Black, // Color(0xFF101010),
        onBackground = Colors.White,
        onSurface = Colors.White, // Colors.Gray6,
        surfaceVariant = Gray900,
        surfaceContainer = Colors.White16,
        onPrimary = Colors.Black,
        onSecondary = Colors.White,
        outlineVariant = Colors.White10, // divider default
        scrim = Colors.Black,
    )
}

@Composable
internal fun AppThemeSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isSystemInDarkTheme = true // isSystemInDarkTheme() // use computed value for dark theme
    AppTheme(inDarkTheme = isSystemInDarkTheme) {
        Surface(
            content = content,
            modifier = modifier,
        )
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
