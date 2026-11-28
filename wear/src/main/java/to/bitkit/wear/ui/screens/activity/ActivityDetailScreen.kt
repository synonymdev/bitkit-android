package to.bitkit.wear.ui.screens.activity

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.models.PaymentState
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.LargeAmountRow
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.screens.home.bitcoinAmount
import to.bitkit.wear.ui.theme.Colors

/** The size of the status icon beside the status word. */
private val STATUS_ICON_SIZE = 18.dp

/**
 * One payment of the activity list: its amount, the fee of a send, when it was made and its status. It reads the
 * payment from the list's [viewModel], which belongs to Home.
 */
@Composable
fun ActivityDetailScreen(
    paymentId: String,
    viewModel: ActivityViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val payment = uiState.payment(paymentId) ?: return
    Content(payment = payment, isAmbient = isAmbient(), modifier = modifier)
}

@Composable
private fun Content(
    payment: WatchPayment,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        if (!isAmbient) {
            BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                LargeAmountRow(
                    amount = bitcoinAmount(payment.amountSats.toLong()),
                    maxWidth = maxWidth,
                    amountTag = "WatchActivityDetailAmount",
                    symbolTag = "WatchActivityDetailSymbol",
                )
            }
        }
        if (payment.isSent && payment.feeSats > 0uL && !isAmbient) {
            val fee = bitcoinAmount(payment.feeSats.toLong())
            Line(
                text = stringResource(R.string.send__fee, "${fee.symbol} ${fee.text}"),
                modifier = Modifier.testTag("WatchActivityDetailFee")
            )
        }
        val context = LocalContext.current
        Line(
            text = DateUtils.formatDateTime(
                context,
                payment.timestampSecs.toLong() * DateUtils.SECOND_IN_MILLIS,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            ),
            modifier = Modifier.testTag("WatchActivityDetailTime")
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DirectionIcon(payment = payment, modifier = Modifier.size(STATUS_ICON_SIZE))
            Text(
                text = stringResource(payment.state.text()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("WatchActivityDetailStatus")
            )
        }
    }
}

private fun PaymentState.text(): Int = when (this) {
    PaymentState.Completed -> R.string.activity__status_completed
    PaymentState.Pending -> R.string.activity__status_pending
    PaymentState.Failed -> R.string.activity__status_failed
}

@Composable
private fun Line(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Colors.White64,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(payment = PreviewPayment, isAmbient = false)
    }
}
