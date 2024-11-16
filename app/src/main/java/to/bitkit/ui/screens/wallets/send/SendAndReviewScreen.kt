package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ui.components.LabelText
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.DarkModePreview
import to.bitkit.ui.shared.util.LightModePreview
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendAndReviewScreen(
    uiState: SendUiState,
    onBack: () -> Unit,
    onEvent: (SendEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        SheetTopBar(stringResource(R.string.title_send_review)) {
            onBack()
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            LabelText(text = stringResource(R.string.label_amount))
            Text(
                text = "${uiState.amount}", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LabelText(text = stringResource(R.string.label_to))
            Text(text = uiState.address.ellipsisMiddle(25))

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.height(IntrinsicSize.Min)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable { onEvent(SendEvent.SpeedAndFee) }
                        .padding(top = 32.dp)
                ) {
                    LabelText(text = stringResource(R.string.label_speed))
                    Text(text = "Todo Normal (₿ 210)")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable { onEvent(SendEvent.SpeedAndFee) }
                        .padding(top = 32.dp)
                ) {
                    LabelText(text = stringResource(R.string.label_confirms_in))
                    Text(text = "Todo ± 20-60 minutes")
                    Spacer(modifier = Modifier.weight(1f))
                    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column {
                LabelText(text = stringResource(R.string.label_tags))
                Text(text = "Todo")
            }

            Spacer(modifier = Modifier.weight(1f))
            PrimaryButton(
                text = stringResource(R.string.pay),
                onClick = { onEvent(SendEvent.SwipeToPay) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@LightModePreview
@DarkModePreview
@Composable
private fun SendAndReviewPreview() {
    AppThemeSurface {
        SendAndReviewScreen(
            uiState = SendUiState(
                amount = 1234uL,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
            ),
            onBack = {},
            onEvent = {},
        )
    }
}
