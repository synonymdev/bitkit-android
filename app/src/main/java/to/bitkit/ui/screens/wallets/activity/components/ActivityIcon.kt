package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.LightningActivity
import uniffi.bitkitcore.OnchainActivity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType

@Composable
fun ActivityIcon(
    activity: Activity,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    val isLightning = activity is Activity.Lightning
    val status: PaymentState? = when (activity) {
        is Activity.Lightning -> activity.v1.status
        is Activity.Onchain -> null
    }
    val txType: PaymentType = when (activity) {
        is Activity.Lightning -> activity.v1.txType
        is Activity.Onchain -> activity.v1.txType
    }
    val arrowIcon = painterResource(if (txType == PaymentType.SENT) R.drawable.ic_sent else R.drawable.ic_received)

    if (isLightning) {
        when (status) {
            PaymentState.FAILED -> {
                CircularIcon(
                    icon = painterResource(R.drawable.ic_x),
                    iconColor = Colors.Purple,
                    backgroundColor = Colors.Purple16,
                    size = size,
                    modifier = modifier,
                )
            }

            PaymentState.PENDING -> {
                CircularIcon(
                    icon = painterResource(R.drawable.ic_hourglass_simple),
                    iconColor = Colors.Purple,
                    backgroundColor = Colors.Purple16,
                    size = size,
                    modifier = modifier,
                )
            }

            else -> {
                CircularIcon(
                    icon = arrowIcon,
                    iconColor = Colors.Purple,
                    backgroundColor = Colors.Purple16,
                    size = size,
                    modifier = modifier,
                )
            }
        }
    } else {
        val isTransfer = (activity as? Activity.Onchain)?.v1?.isTransfer == true
        val onChainIcon = if (isTransfer) painterResource(R.drawable.ic_transfer) else arrowIcon

        CircularIcon(
            icon = onChainIcon,
            iconColor = Colors.Brand,
            backgroundColor = Colors.Brand16,
            size = size,
            modifier = modifier,
        )
    }
}

@Composable
fun CircularIcon(
    icon: Painter,
    iconColor: Color,
    backgroundColor: Color,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Companion.Center,
        modifier = modifier
            .size(size)
            .background(backgroundColor, CircleShape)
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            // Lightning Sent Succeeded
            ActivityIcon(
                activity = Activity.Lightning(
                    v1 = LightningActivity(
                        id = "test-lightning-1",
                        txType = PaymentType.SENT,
                        status = PaymentState.SUCCEEDED,
                        value = 50000uL,
                        fee = 1uL,
                        invoice = "lnbc...",
                        message = "",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        preimage = null,
                        createdAt = null,
                        updatedAt = null,
                    )
                )
            )

            // Lightning Received Failed
            ActivityIcon(
                activity = Activity.Lightning(
                    v1 = LightningActivity(
                        id = "test-lightning-2",
                        txType = PaymentType.RECEIVED,
                        status = PaymentState.FAILED,
                        value = 50000uL,
                        fee = 1uL,
                        invoice = "lnbc...",
                        message = "",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        preimage = null,
                        createdAt = null,
                        updatedAt = null,
                    )
                )
            )

            // Lightning Pending
            ActivityIcon(
                activity = Activity.Lightning(
                    v1 = LightningActivity(
                        id = "test-lightning-3",
                        txType = PaymentType.SENT,
                        status = PaymentState.PENDING,
                        value = 50000uL,
                        fee = 1uL,
                        invoice = "lnbc...",
                        message = "",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        preimage = null,
                        createdAt = null,
                        updatedAt = null,
                    )
                )
            )

            // Onchain Received
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity(
                        id = "test-onchain-1",
                        txType = PaymentType.RECEIVED,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        feeRate = 8uL,
                        address = "bc1...",
                        confirmed = true,
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        isBoosted = false,
                        isTransfer = false,
                        doesExist = true,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                        channelId = null,
                        transferTxId = null,
                        createdAt = null,
                        updatedAt = null,
                    )
                )
            )

            // Onchain Transfer
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity(
                        id = "test-onchain-2",
                        txType = PaymentType.SENT,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        feeRate = 8uL,
                        address = "bc1...",
                        confirmed = true,
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        isBoosted = false,
                        isTransfer = true,
                        doesExist = true,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                        channelId = null,
                        transferTxId = "transferTxId",
                        createdAt = null,
                        updatedAt = null,
                    )
                )
            )
        }
    }
}
