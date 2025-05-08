package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors
import uniffi.bitkitcore.Activity
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
    val icon = if (txType == PaymentType.SENT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward

    if (isLightning) {
        when (status) {
            PaymentState.FAILED -> {
                CircularIcon(
                    icon = Icons.Default.Close,
                    iconColor = Colors.Purple,
                    backgroundColor = Colors.Purple16,
                    size = size,
                    modifier = modifier,
                )
            }

            PaymentState.PENDING -> {
                CircularIcon(
                    icon = Icons.Default.HourglassEmpty,
                    iconColor = Colors.Purple,
                    backgroundColor = Colors.Purple16,
                    size = size,
                    modifier = modifier,
                )
            }

            else -> {
                CircularIcon(
                    icon = icon,
                    iconColor = Colors.Purple,
                    backgroundColor = Colors.Purple16,
                    size = size,
                    modifier = modifier,
                )
            }
        }
    } else {
        CircularIcon(
            icon = icon,
            iconColor = Colors.Brand,
            backgroundColor = Colors.Brand16,
            size = size,
            modifier = modifier,
        )
    }
}

// TODO use our custom icons
@Composable
fun CircularIcon(
    icon: ImageVector,
    iconColor: Color,
    backgroundColor: Color,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Companion.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
