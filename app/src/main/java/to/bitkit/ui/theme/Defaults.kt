package to.bitkit.ui.theme

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object AppTextFieldDefaults {
    val noIndicatorColors: TextFieldColors
        @Composable
        get() = TextFieldDefaults.colors(
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
        )

    val transparent: TextFieldColors
        @Composable
        get() = noIndicatorColors.copy(
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

    val semiTransparent: TextFieldColors
        @Composable
        get() = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = Colors.White10,
            unfocusedContainerColor = Colors.White10,
            cursorColor = Colors.Brand,
            errorCursorColor = Colors.Brand,
            errorIndicatorColor = Color.Transparent,
            errorContainerColor = Colors.White10,
            errorTextColor = Colors.Red,
            errorPrefixColor = Colors.Red
        )
}

object AppButtonDefaults {
    val primaryColors: ButtonColors
        @Composable
        get() = ButtonDefaults.buttonColors(
            containerColor = Colors.White16,
            disabledContainerColor = Color.Transparent,
            contentColor = Colors.White,
            disabledContentColor = Colors.White32,
        )

    val secondaryColors: ButtonColors
        @Composable
        get() = ButtonDefaults.outlinedButtonColors(
            containerColor = Colors.White.copy(alpha = 0.01f),
            contentColor = Colors.White80,
            disabledContentColor = Colors.White32,
        )

    val tertiaryColors: ButtonColors
        @Composable
        get() = ButtonDefaults.textButtonColors(
            contentColor = Colors.White80,
            disabledContentColor = Colors.White32,
        )
}

object AppSwitchDefaults {
    val colors: SwitchColors
        @Composable
        get() = SwitchDefaults.colors(
            // When checked (ON state)
            checkedThumbColor = Colors.White,
            checkedTrackColor = Colors.Brand,
            checkedBorderColor = Colors.Brand,
            checkedIconColor = Colors.Brand,

            // When unchecked (OFF state)
            uncheckedThumbColor = Colors.White,
            uncheckedTrackColor = Colors.Gray4,
            uncheckedBorderColor = Colors.Gray4,
            uncheckedIconColor = Colors.Gray4,
        )

    val colorsPurple: SwitchColors
        @Composable
        get() = SwitchDefaults.colors(
            // When checked (ON state)
            checkedThumbColor = Colors.White,
            checkedTrackColor = Colors.Purple,
            checkedBorderColor = Colors.Purple,
            checkedIconColor = Colors.Purple,

            // When unchecked (OFF state)
            uncheckedThumbColor = Colors.White,
            uncheckedTrackColor = Colors.Gray4,
            uncheckedBorderColor = Colors.Gray4,
            uncheckedIconColor = Colors.Gray4,
        )
}

const val TRANSITION_SCREEN_MS = AnimationConstants.DefaultDurationMillis.toLong() // 300ms
const val TRANSITION_SHEET_MS = 650L

object Insets {
    val Top: Dp
        @Composable
        get() {
            val isPreview = LocalInspectionMode.current
            if (isPreview) return 32.dp
            return WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        }

    val Bottom: Dp
        @Composable
        get() {
            val isPreview = LocalInspectionMode.current
            if (isPreview) return 32.dp
            return WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        }
}

@OptIn(ExperimentalMaterial3Api::class)
val TopBarHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight

val TopBarGradient: Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.5f to Colors.Black,
        1.0f to Color.Transparent,
    ),
)
