package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendAddressScreen(
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SheetTopBar("Send Bitcoin") {
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            Text(
                text = "TO",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Normal,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextField(
                placeholder = { Text("Enter a Bitcoin address or a Lighting invoice") },
                value = inputText,
                onValueChange = { inputText = it },
                minLines = 8,
                colors = AppTextFieldDefaults.noIndicatorColors,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Spacer(modifier = Modifier.height(48.dp))
            FullWidthTextButton(
                enabled = inputText.isNotEmpty(),
                horizontalArrangement = Arrangement.Center,
                onClick = { onEvent(SendEvent.AddressContinue(inputText)) }
            ) {
                Text("Continue")
            }
        }
    }

}

@Preview(showBackground = true)
@Composable
private fun SendEnterManuallyScreenPreview() {
    AppThemeSurface {
        SendAddressScreen(
            onBack = {},
            onEvent = {},
        )
    }
}
