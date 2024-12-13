package to.bitkit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ContentAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

val Gray100 = Color(0xFFF4F4F4)
val Gray300 = Color(0xFFBDBDBD)
val Gray400 = Color(0xFFABABAB)
val gray900 = Color(0xFF212121)
val Brand50 = Color(0xFFFFF1EE)
val Brand500 = Color(0xFFEC5428)
val Blue500 = Color(0xFF0085FF)
val Red500 = Color(0xFFF44336)
val Green500 = Color(0xFF4CAF50)
val Orange500 = Color(0xFFFF9800)
val Purple500 = Color(0xFF9C27B0)
val Purple700 = Color(0xFFB95CE8)

val secondaryColor: Color
    @Composable get() = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.medium)

private object ColorPalette {
    @Stable
    val Light = lightColorScheme(
        primary = Brand500,
        primaryContainer = Brand50,
        secondary = Purple700,
        background = Color.White,
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
        primary = Brand500,
        secondary = Purple700,
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
