package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtTransfer
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.SecondaryButton

@Composable
internal fun UsdtSupportActions(details: String, enabled: Boolean = true) {
    val app = appViewModel ?: return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SecondaryButton(
            text = stringResource(R.string.wallet__send_error_support),
            onClick = { app.navigateToReportIssue(details) },
            enabled = enabled,
            modifier = Modifier.testTag("UsdtContactSupport")
        )
        SecondaryButton(
            text = stringResource(R.string.usdt__copy_details),
            onClick = {
                context.setClipboardText(details)
                app.toast(type = Toast.ToastType.SUCCESS, title = context.getString(R.string.common__copied))
            },
            enabled = enabled,
            modifier = Modifier.testTag("UsdtCopyDetails")
        )
    }
}

internal val UsdtTransfer.supportDetails: String
    get() = buildList {
        add("Asset: USDT")
        add("Bridge: ${if (orchestra == null) "USDT0" else "Orchestra"}")
        add("Source network: Arbitrum One")
        add("Recipient network: ${destination.label}")
        add("Status: $status")
        orchestra?.quoteId?.let { add("Bridge reference: $it") }
        txHash?.let { add("Source transaction: $it") }
        bridgeGuid?.let { add("LayerZero message: $it") }
        orchestra?.destinationTx?.let { add("Destination transaction: $it") }
        orchestra?.refundTx?.let { add("Refund transaction: $it") }
    }.joinToString("\n")

internal val UsdtDepositDetail.supportDetails: String
    get() = buildList {
        add("Asset: ${deposit.asset}")
        add("Bridge: Orchestra")
        add("Source network: ${deposit.network}")
        add("Recipient network: Arbitrum One")
        add("Deposit reference: ${deposit.id}")
        add("Status: ${order?.status ?: deposit.status}")
        add("Source transaction: ${deposit.sourceTx}")
        statusCode?.let { add("Status code: $it") }
        order?.destinationTx?.let { add("Destination transaction: $it") }
        (order?.refundTx ?: deposit.refundTx)?.let { add("Refund transaction: $it") }
    }.joinToString("\n")
