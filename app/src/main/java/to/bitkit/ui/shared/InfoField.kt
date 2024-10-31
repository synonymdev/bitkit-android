package to.bitkit.ui.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import to.bitkit.ui.shared.util.ellipsisVisualTransformation

@Composable
internal fun InfoField(
    value: String,
    label: String,
    maxLength: Int,
    trailingIcon: @Composable (() -> Unit)? = null,
    onValueChange: (String) -> Unit = {},
) {
    OutlinedTextField(
        label = { Text(label) },
        value = value,
        onValueChange = onValueChange,
        trailingIcon = trailingIcon,
        enabled = false,
        readOnly = true,
        singleLine = true,
        colors = MaterialTheme.colorScheme.onBackground.let {
            OutlinedTextFieldDefaults.colors(
                disabledTextColor = it,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = it,
                disabledTrailingIconColor = it,
            )
        },
        textStyle = MaterialTheme.typography.labelSmall,
        visualTransformation = ellipsisVisualTransformation(maxLength),
        modifier = Modifier.fillMaxWidth()
    )
}
