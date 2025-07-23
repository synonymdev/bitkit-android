package to.bitkit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TextInput(
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    isError: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    TextField(
        placeholder = {
            if (!placeholder.isNullOrEmpty()) {
                BodySSB(
                    placeholder,
                    color = Colors.White64,
                )
            } else null
        },
        isError = isError,
        textStyle = AppTextStyles.BodySSB,
        value = value,
        onValueChange = onValueChange,
        maxLines = maxLines,
        minLines = minLines,
        singleLine = singleLine,
        colors = AppTextFieldDefaults.semiTransparent,
        shape = AppShapes.small,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        visualTransformation = visualTransformation,
        modifier = modifier,
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ScreenColumn(
            modifier = Modifier.padding(vertical = 24.dp, horizontal = 16.dp)
        ) {
            TextInput(
                value = "Input text value",
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
            )

            VerticalSpacer(12.dp)
            TextInput(
                value = "",
                onValueChange = {},
                placeholder = "Placeholder text",
                modifier = Modifier.fillMaxWidth(),
            )

            VerticalSpacer(12.dp)
            TextInput(
                value = "Error text",
                onValueChange = {},
                isError = true,
                modifier = Modifier.fillMaxWidth(),
            )

            VerticalSpacer(12.dp)
            TextInput(
                value = "First line of text \nSecond line of text",
                onValueChange = {},
                minLines = 3,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
