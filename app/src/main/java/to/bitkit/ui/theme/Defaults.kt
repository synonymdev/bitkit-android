package to.bitkit.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

@Immutable
object AppTextFieldDefaults {
    @Stable
    val noIndicatorColors: TextFieldColors
        @Composable
        get() = TextFieldDefaults.colors(
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
        )
    @Stable
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
    @Stable
    val semiTransparent: TextFieldColors
        @Composable
        get() = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = Colors.White10,
            unfocusedContainerColor = Colors.White10,
        )
}

@Immutable
object AppButtonDefaults {
    @Stable
    val primaryColors: ButtonColors
        @Composable
        get() = ButtonDefaults.buttonColors(
            containerColor = Colors.White16,
            disabledContainerColor = Color.Transparent,
            contentColor = Colors.White,
            disabledContentColor = Colors.White32,
        )

    @Stable
    val secondaryColors: ButtonColors
        @Composable
        get() = ButtonDefaults.outlinedButtonColors(
            contentColor = Colors.White80,
            disabledContentColor = Colors.White32,
        )

    @Stable
    val tertiaryColors: ButtonColors
        @Composable
        get() = ButtonDefaults.textButtonColors(
            contentColor = Colors.White80,
            disabledContentColor = Colors.White32,
        )
}
