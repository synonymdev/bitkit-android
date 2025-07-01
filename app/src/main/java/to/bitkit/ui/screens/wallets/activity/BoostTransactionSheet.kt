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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.Activity
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
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

    var isDefaultFee by remember { mutableStateOf(false) }

    BoostTransactionContent(
        modifier = modifier,
        sats = item.v1.fee.toLong(),
        estimateTime = "±10-20 minutes", //TODO IMPLEMENT TIME CONFIRMATION CALC
        isDefaultFee = isDefaultFee,
        onClickEdit = {
            isDefaultFee = !isDefaultFee
        },
        onClickUseSuggestedFee = {

        },
        onChangeAmount = { increase ->

        },
    )
}

@Composable
fun BoostTransactionContent(
    modifier: Modifier = Modifier,
    sats: Long,
    estimateTime: String,
    onClickEdit: () -> Unit,
    onClickUseSuggestedFee: () -> Unit,
    onChangeAmount: (Boolean) -> Unit,
    isDefaultFee: Boolean,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__boost_title))

        val bodyText = if (isDefaultFee) R.string.wallet__boost_fee_recomended else R.string.wallet__boost_fee_custom

        BodyS(text = stringResource(bodyText), color = Colors.White64)

        VerticalSpacer(24.dp)

        if (isDefaultFee) {
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
                    BodySSB(text = estimateTime, color = Colors.White64)
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clickable { onClickEdit() },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        BodyMSB(
                            text = rememberMoneyText(sats = sats)
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
                            sats = sats,
                            reversed = true
                        ).orEmpty().withAccent(defaultColor = Colors.White64).toString(),
                        color = Colors.White64
                    )
                }
            }

            VerticalSpacer(68.dp)
        } else {

            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    BodyMSB(
                        text = rememberMoneyText(sats = sats)
                            .orEmpty()
                            .withAccent(defaultColor = Colors.White).toString() + "/vbyte",
                        color = Colors.White
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BodySSB(
                            text = rememberMoneyText(
                                sats = sats,
                                reversed = true
                            ).orEmpty().withAccent(defaultColor = Colors.White64).toString(),
                            color = Colors.White64
                        )

                        BodySSB(
                            text = estimateTime,
                            color = Colors.White64
                        )
                    }
                }
            }

            VerticalSpacer(16.dp)

            PrimaryButton(
                text = stringResource(R.string.wallet__boost_recomended_button),
                fullWidth = false,
                onClick = onClickUseSuggestedFee,
                size = ButtonSize.Small
            )

            VerticalSpacer(16.dp)
        }

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
        BoostTransactionContent(
            sats = 4250L,
            estimateTime = "±10-20 minutes",
            onClickEdit = {},
            onClickUseSuggestedFee = {},
            onChangeAmount = {},
            isDefaultFee = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        BoostTransactionContent(
            sats = 4250L,
            estimateTime = "±10-20 minutes",
            onClickEdit = {},
            onClickUseSuggestedFee = {},
            onChangeAmount = {},
            isDefaultFee = false
        )
    }
}
