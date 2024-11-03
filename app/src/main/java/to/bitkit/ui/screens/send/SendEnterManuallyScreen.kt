package to.bitkit.ui.screens.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendEnterManuallyScreen(
    viewModel: WalletViewModel?,
) {
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        TextField(
            placeholder = { Text("Enter a Bitcoin address or a Lighting invoice") },
            value = inputText,
            onValueChange = { inputText = it },
            minLines = 8,
            colors = TextFieldDefaults.colors(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Spacer(modifier = Modifier.height(48.dp))
        HorizontalDivider()
        FullWidthTextButton(
            enabled = inputText.isNotEmpty(),
            horizontalArrangement = Arrangement.Center,
            onClick = { viewModel?.onSendManually(inputText) }
        ) {
            Text("Continue")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SendEnterManuallyScreenPreview() {
    AppThemeSurface {
        SendEnterManuallyScreen(null)
    }
}
