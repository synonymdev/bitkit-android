package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendAndReviewScreen(
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
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
            LabelText(text = "AMOUNT")
            Text(text = "${uiState.amount}")

            Spacer(modifier = Modifier.height(16.dp))

            LabelText(text = "TO")
            Text(text = uiState.address.ellipsisMiddle(25))

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row {
                Column(modifier = Modifier.weight(1f)) {
                    LabelText(text = "SPEED AND FEE")
                    Text(text = "Normal (₿ 210)")
                }
                Column(modifier = Modifier.weight(1f)) {
                    LabelText(text = "CONFIRMS IN")
                    Text(text = "± 20-60 minutes")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Column {
                LabelText(text = "TAGS")
            }

            Spacer(modifier = Modifier.weight(1f))
            FullWidthTextButton(
                horizontalArrangement = Arrangement.Center,
                onClick = { onEvent(SendEvent.SwipeToPay) }
            ) {
                Text(text = "Pay")
            }
        }
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun SendAndReviewPreview() {
    AppThemeSurface {
        SendAndReviewScreen(
            onBack = {},
            onEvent = {},
            uiState = SendUiState(
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
            ),
        )
    }
}
