package to.bitkit.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme

private val WearColorScheme = ColorScheme(
    primary = Colors.Brand,
    primaryDim = Colors.Brand,
    primaryContainer = Colors.Brand,
    onPrimary = Colors.White,
    onPrimaryContainer = Colors.White,
    secondaryContainer = Colors.Gray5,
    onSecondaryContainer = Colors.White,
    surfaceContainerLow = Colors.Gray7,
    surfaceContainer = Colors.Gray6,
    surfaceContainerHigh = Colors.Gray5,
    onSurface = Colors.White,
    onSurfaceVariant = Colors.Gray1,
    outline = Colors.Gray3,
    outlineVariant = Colors.Gray4,
    background = Colors.Black,
    onBackground = Colors.White,
    error = Colors.Red,
    errorContainer = Colors.Red,
    onError = Colors.White,
    onErrorContainer = Colors.White,
)

@Composable
fun BitkitWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WearColorScheme,
        typography = WearTypography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
