package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import to.bitkit.R
import to.bitkit.ext.create
import to.bitkit.ext.doesExist
import to.bitkit.ext.isBoosting
import to.bitkit.ext.isTransfer
import to.bitkit.ext.paymentState
import to.bitkit.ext.txType
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ActivityIcon(
    activity: Activity,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    isCpfpChild: Boolean = false,
) {
    val isLightning = activity is Activity.Lightning
    val isBoosting = activity.isBoosting()
    val status = activity.paymentState()
    val txType = activity.txType()
    val arrowIcon = painterResource(if (txType == PaymentType.SENT) R.drawable.ic_sent else R.drawable.ic_received)

    when {
        isCpfpChild || isBoosting -> {
            CircularIcon(
                icon = painterResource(R.drawable.ic_timer_alt),
                iconColor = Colors.Yellow,
                backgroundColor = Colors.Yellow16,
                size = size,
                modifier = modifier.testTag("BoostingIcon"),
            )
        }

        isLightning -> ActivityIconLightning(status, size, arrowIcon, modifier)
        else -> ActivityIconOnchain(activity, arrowIcon, size, modifier)
    }
}

@Composable
private fun ActivityIconOnchain(
    activity: Activity,
    arrowIcon: Painter,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val isTransfer = activity.isTransfer()
    val isTransferFromSpending = isTransfer && activity.txType() == PaymentType.RECEIVED
    val transferIconColor = if (isTransferFromSpending) Colors.Purple else Colors.Brand
    val transferBackgroundColor = if (isTransferFromSpending) Colors.Purple16 else Colors.Brand16

    CircularIcon(
        icon = when {
            !activity.doesExist() -> painterResource(R.drawable.ic_x)
            isTransfer -> painterResource(R.drawable.ic_transfer)
            else -> arrowIcon
        },
        iconColor = if (isTransfer) transferIconColor else Colors.Brand,
        backgroundColor = if (isTransfer) transferBackgroundColor else Colors.Brand16,
        size = size,
        modifier = modifier.testTag(if (isTransfer) "TransferIcon" else "ActivityIcon"),
    )
}

@Composable
private fun ActivityIconLightning(
    status: PaymentState?,
    size: Dp,
    arrowIcon: Painter,
    modifier: Modifier = Modifier,
) {
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
}

@Composable
fun CircularIcon(
    icon: Painter,
    iconColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
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
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            // Lightning Sent Succeeded
            ActivityIcon(
                activity = Activity.Lightning(
                    v1 = LightningActivity.create(
                        id = "test-lightning-1",
                        txType = PaymentType.SENT,
                        status = PaymentState.SUCCEEDED,
                        value = 50000uL,
                        invoice = "lnbc...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        fee = 1uL,
                    )
                )
            )

            // Lightning Received Failed
            ActivityIcon(
                activity = Activity.Lightning(
                    v1 = LightningActivity.create(
                        id = "test-lightning-2",
                        txType = PaymentType.RECEIVED,
                        status = PaymentState.FAILED,
                        value = 50000uL,
                        invoice = "lnbc...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        fee = 1uL,
                    )
                )
            )

            // Lightning Pending
            ActivityIcon(
                activity = Activity.Lightning(
                    v1 = LightningActivity.create(
                        id = "test-lightning-3",
                        txType = PaymentType.SENT,
                        status = PaymentState.PENDING,
                        value = 50000uL,
                        invoice = "lnbc...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        fee = 1uL,
                    )
                )
            )

            // Onchain Received
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity.create(
                        id = "test-onchain-1",
                        txType = PaymentType.RECEIVED,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        address = "bc1...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        confirmed = true,
                        feeRate = 8uL,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                    )
                )
            )

            // Onchain BOOST CPFP
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity.create(
                        id = "test-onchain-1",
                        txType = PaymentType.RECEIVED,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        address = "bc1...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        feeRate = 8uL,
                        isBoosted = true,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                    )
                )
            )

            // Onchain BOOST RBF
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity.create(
                        id = "test-onchain-1",
                        txType = PaymentType.SENT,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        address = "bc1...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        feeRate = 8uL,
                        isBoosted = true,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                    )
                )
            )

            // Onchain Transfer
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity.create(
                        id = "test-onchain-2",
                        txType = PaymentType.SENT,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        address = "bc1...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        confirmed = true,
                        feeRate = 8uL,
                        isTransfer = true,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                        transferTxId = "transferTxId",
                    )
                )
            )

            // Onchain Removed
            ActivityIcon(
                activity = Activity.Onchain(
                    v1 = OnchainActivity.create(
                        id = "test-onchain-2",
                        txType = PaymentType.SENT,
                        txId = "abc123",
                        value = 100000uL,
                        fee = 500uL,
                        address = "bc1...",
                        timestamp = (System.currentTimeMillis() / 1000).toULong(),
                        confirmed = true,
                        feeRate = 8uL,
                        isBoosted = true,
                        doesExist = false,
                        confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                        transferTxId = "transferTxId",
                    )
                )
            )
        }
    }
}
