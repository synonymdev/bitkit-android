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
    trailingIcon: @Composable (() -> Unit)? = null,
    onValueChange: (String) -> Unit = {},
) {
    OutlinedTextField(
        label = { Text(label) },
        value = value,
        onValueChange = onValueChange,
        trailingIcon = trailingIcon,
        readOnly = true,
        singleLine = true,
        colors = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f).let {
            OutlinedTextFieldDefaults.colors(
                unfocusedTextColor = it,
                focusedTextColor = it,
                focusedBorderColor = it,
                focusedLabelColor = it.copy(alpha = 1f),
            )
        },
        textStyle = MaterialTheme.typography.labelSmall,
        visualTransformation = ellipsisVisualTransformation(40),
        modifier = Modifier.fillMaxWidth(),
    )
}
