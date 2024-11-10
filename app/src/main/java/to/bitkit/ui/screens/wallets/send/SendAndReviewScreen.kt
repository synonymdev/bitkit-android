package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ext.takeEnds
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendAndReviewScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    uiState: SendUiState,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SheetTopBar("Review & Send") {
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(text = "Amount", fontWeight = FontWeight.Bold)
            Text(text = "${uiState.amount}")
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "To", fontWeight = FontWeight.Bold)
            Text(text = uiState.address.takeEnds(12))

            Spacer(modifier = Modifier.weight(1f))
            FullWidthTextButton(
                horizontalArrangement = Arrangement.Center,
                onClick = { onContinue() }
            ) {
                Text(text = "Pay")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SendAndReviewPreview() {
    AppThemeSurface {
        SendAndReviewScreen(
            onBack = {},
            onContinue = {},
            uiState = SendUiState(
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
            ),
        )
    }
}
