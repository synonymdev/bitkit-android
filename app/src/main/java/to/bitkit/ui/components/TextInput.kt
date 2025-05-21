package to.bitkit.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun TextInput(
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    isError: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(
        placeholder = {
            if (!placeholder.isNullOrEmpty()) {
                BodyMSB(
                    placeholder,
                    color = Colors.White64
                )
            } else null
        },
        isError = isError,
        textStyle = LocalTextStyle.current.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.4.sp,
            fontFamily = InterFontFamily,
            textAlign = TextAlign.Start,
        ),
        value = value,
        onValueChange = onValueChange,
        maxLines = maxLines,
        singleLine = singleLine,
        colors = AppTextFieldDefaults.semiTransparent,
        shape = AppShapes.small,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
    )
}
