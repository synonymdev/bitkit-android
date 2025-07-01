package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import to.bitkit.R
import to.bitkit.ext.totalValue
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun BoostTransactionSheet(
    modifier: Modifier = Modifier,
    item: Activity.Onchain,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__boost_title))

        BodyS(text = stringResource(R.string.wallet__boost_fee_recomended), color = Colors.White64)

        VerticalSpacer(24.dp)

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(R.drawable.ic_timer_alt_yellow),
                contentDescription = null
            )

            HorizontalSpacer(16.dp)

            Column(modifier = Modifier.weight(1f)) {
                BodyMSB(text = stringResource(R.string.wallet__boost), color = Colors.White)
                BodySSB(text = "±10-20 minutes", color = Colors.White64) //TODO IMPLEMENT TIME CONFIRMATION CALC
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.clickable {}, //TODO IMPLEMENT
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BodyMSB(
                        text = rememberMoneyText(sats = item.totalValue().toLong())
                            .orEmpty()
                            .withAccent(defaultColor = Colors.White).toString(),
                        color = Colors.White
                    )

                    Icon(
                        painter = painterResource(R.drawable.ic_pencil_simple),
                        tint = Colors.White,
                        contentDescription = stringResource(R.string.common__edit),
                        modifier = Modifier.size(16.dp)
                    )
                }

                BodySSB(
                    text = rememberMoneyText(
                        sats = item.totalValue().toLong(),
                        reversed = true
                    ).orEmpty().withAccent(defaultColor = Colors.White64).toString(),
                    color = Colors.White64
                )
            }
        }

        VerticalSpacer(68.dp)

        SwipeToConfirm(
            text = stringResource(R.string.wallet__boost_swipe),
            color = Colors.Yellow,
            endIcon = R.drawable.ic_timer_alt_yellow,
            endIconTint = Color.Unspecified,
            onConfirm = {

            },
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(8.dp)
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BoostTransactionSheet(
            item = Activity.Onchain(
                v1 = OnchainActivity(
                    id = "test-onchain-1",
                    txType = PaymentType.RECEIVED,
                    txId = "abc123",
                    value = 100000UL,
                    fee = 500UL,
                    feeRate = 8UL,
                    address = "bc1...",
                    confirmed = true,
                    timestamp = (System.currentTimeMillis() / 1000 - 3600).toULong(),
                    isBoosted = false,
                    isTransfer = false,
                    doesExist = true,
                    confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                    channelId = null,
                    transferTxId = null,
                    createdAt = null,
                    updatedAt = null,
                )
            ),
        )
    }
}
