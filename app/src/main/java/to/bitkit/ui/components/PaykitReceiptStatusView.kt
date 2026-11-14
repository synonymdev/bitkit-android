package to.bitkit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.repositories.PaykitReceiptStatus
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

/** The Paykit receipt line of a received payment, with a retry when it failed. */
@Composable
fun PaykitReceiptStatusView(
    status: PaykitReceiptStatus,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.testTag("PaykitReceiptStatus"),
    ) {
        CaptionB(
            text = stringResource(
                when (status) {
                    PaykitReceiptStatus.Sending -> R.string.wallet__receipt_sending
                    PaykitReceiptStatus.Sent -> R.string.wallet__receipt_sent
                    PaykitReceiptStatus.Failed -> R.string.wallet__receipt_failed
                }
            ),
            color = if (status == PaykitReceiptStatus.Failed) Colors.Red else Colors.White64,
            maxLines = 1,
        )
        if (status == PaykitReceiptStatus.Failed) {
            CaptionB(
                text = stringResource(R.string.common__retry),
                color = Colors.Brand,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onRetryClick)
                    .testTag("PaykitReceiptRetry")
            )
        }
    }
}

/** An activity row's subtitle followed by the receipt line of its received payment, when it has one. */
@Composable
fun ActivitySubtitleWithReceipt(
    subtitle: String,
    receiptStatus: PaykitReceiptStatus?,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        CaptionB(
            text = subtitle,
            color = Colors.White64,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (receiptStatus != null) {
            CaptionB(text = "·", color = Colors.White64, maxLines = 1)
            PaykitReceiptStatusView(status = receiptStatus, onRetryClick = onRetryClick)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ActivitySubtitleWithReceipt(
            subtitle = "14 Nov, 03:10",
            receiptStatus = PaykitReceiptStatus.Failed,
            onRetryClick = {},
        )
    }
}
