package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.theme.Colors
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface

enum class ChannelStatusUi { PENDING, OPEN, CLOSED }

@Composable
fun LightningChannel(
    capacity: Long,
    localBalance: Long,
    remoteBalance: Long,
    status: ChannelStatusUi = ChannelStatusUi.PENDING,
    showLabels: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val spendingColor = if (status == ChannelStatusUi.CLOSED) Colors.Gray5 else Colors.Purple50
    val spendingAvailableColor = if (status == ChannelStatusUi.CLOSED) Colors.Gray3 else Colors.Purple
    val receivingColor = if (status == ChannelStatusUi.CLOSED) Colors.Gray5 else Colors.White64
    val receivingAvailableColor = if (status == ChannelStatusUi.CLOSED) Colors.Gray3 else Colors.White

    val spendingAvailableFraction = if (capacity > 0) localBalance.toFloat() / capacity else 0f
    val receivingAvailableFraction = if (capacity > 0) remoteBalance.toFloat() / capacity else 0f

    Column(
        modifier = modifier
            .then(if (status == ChannelStatusUi.PENDING) Modifier.alpha(0.5f) else Modifier)
    ) {
        if (showLabels) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Caption13Up(text = stringResource(R.string.lightning__spending_label), color = Colors.White64)
                Caption13Up(text = stringResource(R.string.lightning__receiving_label), color = Colors.White64)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = spendingAvailableColor,
                    modifier = Modifier.size(14.dp)
                )
                MoneyCaptionB(
                    sats = localBalance,
                    color = spendingAvailableColor,
                )
            }
            Row(verticalAlignment = CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = null,
                    tint = receivingAvailableColor,
                    modifier = Modifier.size(14.dp)
                )
                MoneyCaptionB(
                    sats = remoteBalance,
                    color = receivingAvailableColor,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(spendingColor, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(spendingAvailableFraction)
                        .align(Alignment.CenterEnd)
                        .background(spendingAvailableColor, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(receivingColor, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(receivingAvailableFraction)
                        .background(receivingAvailableColor, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                )
            }
        }
    }
}

// region preview

@Preview(showBackground = true)
@Composable
private fun PreviewChannelOpen() {
    AppThemeSurface {
        LightningChannel(
            capacity = 500_000,
            localBalance = 50_000,
            remoteBalance = 450_000,
            status = ChannelStatusUi.OPEN,
            showLabels = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewChannelOpenWithoutLabels() {
    AppThemeSurface {
        LightningChannel(
            capacity = 1_000_000,
            localBalance = 500_000,
            remoteBalance = 500_000,
            status = ChannelStatusUi.OPEN,
            showLabels = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewChannelPending() {
    AppThemeSurface {
        LightningChannel(
            capacity = 1_000_000,
            localBalance = 400_000,
            remoteBalance = 600_000,
            status = ChannelStatusUi.PENDING,
            showLabels = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewChannelClosed() {
    AppThemeSurface {
        LightningChannel(
            capacity = 1_000_000,
            localBalance = 0,
            remoteBalance = 0,
            status = ChannelStatusUi.CLOSED,
            showLabels = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// endregion
