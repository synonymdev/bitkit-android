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
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import to.bitkit.R
import to.bitkit.ext.doesExist
import to.bitkit.ext.isBoosting
import to.bitkit.ext.isTransfer
import to.bitkit.ext.paymentState
import to.bitkit.ext.txType
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ActivityIcon(
    activity: Activity,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    isCpfpChild: Boolean = false,
    isHardware: Boolean = false,
    contact: PubkyProfile? = null,
) {
    val txType = activity.txType()
    val arrowIcon = painterResource(
        id = if (txType == PaymentType.SENT) {
            R.drawable.ic_sent
        } else {
            R.drawable.ic_received
        },
    )

    when {
        isCpfpChild || activity.isBoosting() -> {
            CircularIcon(
                icon = painterResource(R.drawable.ic_timer_alt),
                iconColor = Colors.Yellow,
                backgroundColor = Colors.Yellow16,
                size = size,
                modifier = modifier.testTag("BoostingIcon"),
            )
        }

        contact != null -> PubkyContactAvatar(
            profile = contact,
            size = size,
            testTag = "ActivityContactAvatar",
            modifier = modifier
        )
        activity is Activity.Lightning -> ActivityIconLightning(activity.paymentState(), size, arrowIcon, modifier)
        else -> ActivityIconOnchain(
            txType = txType,
            isTransfer = activity.isTransfer(),
            doesExist = activity.doesExist(),
            arrowIcon = arrowIcon,
            size = size,
            isHardware = isHardware,
            modifier = modifier,
        )
    }
}

@Composable
private fun ActivityIconOnchain(
    txType: PaymentType,
    isTransfer: Boolean,
    doesExist: Boolean,
    arrowIcon: Painter,
    size: Dp,
    isHardware: Boolean,
    modifier: Modifier = Modifier,
) {
    val isTransferFromSpending = isTransfer && txType == PaymentType.RECEIVED
    val (iconColor, backgroundColor) = when {
        isHardware -> Colors.Blue to Colors.Blue16
        isTransferFromSpending -> Colors.Purple to Colors.Purple16
        else -> Colors.Brand to Colors.Brand16
    }
    val tag = when {
        isHardware -> "HardwareActivityIcon"
        isTransfer -> "TransferIcon"
        else -> "ActivityIcon"
    }

    CircularIcon(
        icon = when {
            !doesExist -> painterResource(R.drawable.ic_x)
            isTransfer -> painterResource(R.drawable.ic_transfer)
            else -> arrowIcon
        },
        iconColor = iconColor,
        backgroundColor = backgroundColor,
        size = size,
        modifier = modifier.testTag(tag),
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
            previewActivityItems.forEach { ActivityIcon(activity = it) }
        }
    }
}
