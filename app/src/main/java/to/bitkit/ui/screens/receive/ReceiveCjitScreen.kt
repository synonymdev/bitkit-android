package to.bitkit.ui.screens.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.ui.shared.FullWidthTextButton

@Composable
fun ReceiveCjitScreen(
    viewModel: ReceiveViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onCjitCreated: (String) -> Unit,
    onDismiss: () -> Unit = {},
) {
    DisposableEffect(Unit) {
        onDispose {
            onDismiss()
        }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        OutlinedTextField(
            label = { Text("Amount in sats") },
            value = amount,
            onValueChange = { amount = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Column {
            // TODO: CJIT limits from blocktank info
            Text("TODO: Cjit Limits")
        }
        Spacer(modifier = Modifier.weight(1f))
        HorizontalDivider()
        FullWidthTextButton(
            onClick = {
                amount.toIntOrNull()?.let {
                    viewModel.createCjit(it, "Bitkit")
                }
            },
            horizontalArrangement = Arrangement.Center,
            enabled = !uiState.isCreatingCjit,
        ) {
            Text(text = if (uiState.isCreatingCjit) "Creating..." else "Continue")
        }
        uiState.cjitEntry?.let { entry ->
            LaunchedEffect(entry) {
                onCjitCreated(entry.invoice.request)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReceiveCjitScreenPreview() {
    ReceiveCjitScreen(onCjitCreated = {})
}
