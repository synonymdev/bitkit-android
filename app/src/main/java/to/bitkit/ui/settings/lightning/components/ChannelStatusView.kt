package to.bitkit.ui.settings.lightning.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.BtOpenChannelState
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.BtPaymentState2
import com.synonym.bitkitcore.IBtOrder
import to.bitkit.R
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.mockOrder
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.settings.lightning.ChannelUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChannelStatusView(
    channel: ChannelUi,
    blocktankOrder: IBtOrder?,
) {
    Row(
        verticalAlignment = Alignment.Companion.CenterVertically,
        modifier = Modifier.Companion.padding(vertical = 8.dp)
    ) {
        val statusInfo = getStatusInfo(channel, blocktankOrder)

        Box(
            contentAlignment = Alignment.Companion.Center,
            modifier = Modifier.Companion
                .size(32.dp)
                .background(statusInfo.backgroundColor, CircleShape),
        ) {
            Icon(
                painter = painterResource(statusInfo.iconRes),
                contentDescription = null,
                tint = statusInfo.iconColor,
                modifier = Modifier.Companion.size(16.dp)
            )
        }

        HorizontalSpacer(16.dp)

        BodyMSB(text = statusInfo.statusText, color = statusInfo.statusColor)
    }
}

@Composable
private fun getStatusInfo(
    channel: ChannelUi,
    blocktankOrder: IBtOrder?,
): StatusInfo {
    // Use open/closed status from LDK if available
    when {
        // open
        channel.details.isChannelReady && channel.details.isUsable -> {
            return StatusInfo(
                iconRes = R.drawable.ic_lightning,
                backgroundColor = Colors.Green16,
                iconColor = Colors.Green,
                statusText = stringResource(R.string.lightning__order_state__open),
                statusColor = Colors.Green
            )
        }

        // inactive
        channel.details.isChannelReady && !channel.details.isUsable -> {
            return StatusInfo(
                iconRes = R.drawable.ic_lightning,
                backgroundColor = Colors.Yellow16,
                iconColor = Colors.Yellow,
                statusText = stringResource(R.string.lightning__order_state__inactive),
                statusColor = Colors.Yellow,
            )
        }

        // closed
        // TODO: handle closed channels marking & detection
        // else -> {
        //     return StatusInfo(
        //         iconRes = R.drawable.ic_lightning,
        //         backgroundColor = Colors.White10,
        //         iconColor = Colors.White64,
        //         statusText = stringResource(R.string.lightning__order_state__closed),
        //         statusColor = Colors.White64,
        //     )
        // }
    }

    blocktankOrder?.let { order ->
        if (order.channel?.state == BtOpenChannelState.OPENING) {
            return StatusInfo(
                iconRes = R.drawable.ic_hourglass_simple,
                backgroundColor = Colors.Purple16,
                iconColor = Colors.Purple,
                statusText = stringResource(R.string.lightning__order_state__opening),
                statusColor = Colors.Purple
            )
        }

        if (order.state2 == BtOrderState2.EXPIRED) {
            return StatusInfo(
                iconRes = R.drawable.ic_timer,
                backgroundColor = Colors.Red16,
                iconColor = Colors.Red,
                statusText = stringResource(R.string.lightning__order_state__expired),
                statusColor = Colors.Red
            )
        }

        when (order.payment.state2) {
            BtPaymentState2.CANCELED -> {
                return StatusInfo(
                    iconRes = R.drawable.ic_x,
                    backgroundColor = Colors.Red16,
                    iconColor = Colors.Red,
                    statusText = stringResource(R.string.lightning__order_state__payment_canceled),
                    statusColor = Colors.Red
                )
            }

            BtPaymentState2.REFUND_AVAILABLE -> {
                return StatusInfo(
                    iconRes = R.drawable.ic_arrow_clockwise,
                    backgroundColor = Colors.Yellow16,
                    iconColor = Colors.Yellow,
                    statusText = stringResource(R.string.lightning__order_state__refund_available),
                    statusColor = Colors.Yellow
                )
            }

            BtPaymentState2.REFUNDED -> {
                return StatusInfo(
                    iconRes = R.drawable.ic_arrow_clockwise,
                    backgroundColor = Colors.White10,
                    iconColor = Colors.White64,
                    statusText = stringResource(R.string.lightning__order_state__refunded),
                    statusColor = Colors.White64
                )
            }

            BtPaymentState2.CREATED -> {
                return StatusInfo(
                    iconRes = R.drawable.ic_clock,
                    backgroundColor = Colors.Purple16,
                    iconColor = Colors.Purple,
                    statusText = stringResource(R.string.lightning__order_state__awaiting_payment),
                    statusColor = Colors.Purple
                )
            }

            BtPaymentState2.PAID -> {
                return StatusInfo(
                    iconRes = R.drawable.ic_checkmark,
                    backgroundColor = Colors.Purple16,
                    iconColor = Colors.Purple,
                    statusText = stringResource(R.string.lightning__order_state__paid),
                    statusColor = Colors.Purple
                )
            }
        }
    }

    // fallback for pending channels without order
    if (!channel.details.isChannelReady) {
        return StatusInfo(
            iconRes = R.drawable.ic_hourglass_simple,
            backgroundColor = Colors.Purple16,
            iconColor = Colors.Purple,
            statusText = stringResource(R.string.lightning__order_state__opening),
            statusColor = Colors.Purple
        )
    }

    // closed
    // TODO: handle closed channels marking & detection
    return StatusInfo(
        iconRes = R.drawable.ic_lightning,
        backgroundColor = Colors.White10,
        iconColor = Colors.White64,
        statusText = stringResource(R.string.lightning__order_state__closed),
        statusColor = Colors.White64
    )
}

private data class StatusInfo(
    val iconRes: Int,
    val backgroundColor: Color,
    val iconColor: Color,
    val statusText: String,
    val statusColor: Color,
)

@Preview
@Composable
private fun PreviewOpenChannel() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 1",
                details = createChannelDetails().copy(
                    isChannelReady = true,
                    isUsable = true,
                ),
            ),
            blocktankOrder = null,
        )
    }
}

@Preview
@Composable
private fun PreviewInactiveChannel() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 2",
                details = createChannelDetails().copy(
                    isChannelReady = true,
                    isUsable = false,
                ),
            ),
            blocktankOrder = null,
        )
    }
}

@Preview
@Composable
private fun PreviewOpeningChannel() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 3",
                details = createChannelDetails(),
            ),
            blocktankOrder = null,
        )
    }
}

@Preview
@Composable
private fun PreviewExpiredOrder() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 4",
                details = createChannelDetails(),
            ),
            blocktankOrder = mockOrder().copy(
                state2 = BtOrderState2.EXPIRED,
            ),
        )
    }
}

@Preview
@Composable
private fun PreviewPaymentCanceled() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 5",
                details = createChannelDetails(),
            ),
            blocktankOrder = mockOrder().copy(
                payment = mockOrder().payment.copy(
                    state2 = BtPaymentState2.CANCELED,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun PreviewRefundAvailable() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 6",
                details = createChannelDetails(),
            ),
            blocktankOrder = mockOrder().copy(
                payment = mockOrder().payment.copy(
                    state2 = BtPaymentState2.REFUND_AVAILABLE,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun PreviewRefunded() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 7",
                details = createChannelDetails(),
            ),
            blocktankOrder = mockOrder().copy(
                payment = mockOrder().payment.copy(
                    state2 = BtPaymentState2.REFUNDED,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun PreviewAwaitingPayment() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 8",
                details = createChannelDetails(),
            ),
            blocktankOrder = mockOrder(),
        )
    }
}

@Preview
@Composable
private fun PreviewPaymentPaid() {
    AppThemeSurface {
        ChannelStatusView(
            channel = ChannelUi(
                name = "Connection 9",
                details = createChannelDetails(),
            ),
            blocktankOrder = mockOrder().copy(
                payment = mockOrder().payment.copy(
                    state2 = BtPaymentState2.PAID,
                ),
            ),
        )
    }
}
