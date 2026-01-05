package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.env.Defaults
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun FundingScreen(
    isGeoBlocked: Boolean,
    onTransfer: () -> Unit = {},
    onFund: () -> Unit = {},
    onAdvanced: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    val balances = LocalBalances.current
    val canTransfer = remember(balances.totalOnchainSats) {
        balances.totalOnchainSats >= Defaults.recommendedBaseFee
    }
    var showNoFundsAlert by remember { mutableStateOf(false) }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__funding__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Display(text = stringResource(R.string.lightning__funding__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))

            val text = if (isGeoBlocked) {
                stringResource(R.string.lightning__funding__text_blocked)
            } else {
                stringResource(R.string.lightning__funding__text)
            }
            BodyM(text = text, color = Colors.White64)

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    RectangleButton(
                        label = stringResource(R.string.lightning__funding__button1),
                        icon = R.drawable.ic_transfer,
                        iconTint = Colors.Purple,
                        enabled = canTransfer && !isGeoBlocked,
                        onClick = onTransfer,
                        modifier = Modifier.testTag("FundTransfer")
                    )
                    if (balances.totalOnchainSats == 0uL) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    enabled = balances.totalOnchainSats == 0uL,
                                    interactionSource = null,
                                    indication = null,
                                    onClick = { showNoFundsAlert = true }
                                )
                                .testTag("FundTransfer")
                        )
                    }
                }
                RectangleButton(
                    label = stringResource(R.string.lightning__funding__button2),
                    icon = R.drawable.ic_qr_purple,
                    iconTint = Colors.Purple,
                    iconSize = 13.75.dp,
                    enabled = !isGeoBlocked,
                    onClick = onFund,
                    modifier = Modifier.testTag("FundReceive")
                )
                RectangleButton(
                    label = stringResource(R.string.lightning__funding__button3),
                    icon = R.drawable.ic_share_purple,
                    iconTint = Colors.Purple,
                    onClick = onAdvanced,
                    modifier = Modifier.testTag("FundCustom")
                )
            }
        }
        if (showNoFundsAlert) {
            AlertDialog(
                onDismissRequest = { showNoFundsAlert = false },
                confirmButton = {
                    TextButton(onClick = { showNoFundsAlert = false }) {
                        BodyM(text = stringResource(R.string.common__ok), color = Colors.Purple)
                    }
                },
                title = {
                    BodyMB(text = stringResource(R.string.lightning__no_funds__title))
                },
                text = {
                    BodyM(text = stringResource(R.string.lightning__no_funds__description))
                },
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun FundingScreenPreview() {
    AppThemeSurface {
        FundingScreen(isGeoBlocked = false)
    }
}
