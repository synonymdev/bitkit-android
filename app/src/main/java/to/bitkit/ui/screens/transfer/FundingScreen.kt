package to.bitkit.ui.screens.transfer

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun FundingScreen(
    canFundWithArkade: Boolean = false,
    onTransfer: () -> Unit = {},
    onFund: () -> Unit = {},
    onManual: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    val context = LocalContext.current
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__funding__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        VerticalSpacer(32.dp)
        Display(
            text = stringResource(R.string.lightning__funding__title).withAccent(accentColor = Colors.Purple),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        VerticalSpacer(8.dp)
        BodyM(
            text = stringResource(
                if (canFundWithArkade) {
                    R.string.lightning__funding__text
                } else {
                    R.string.lightning__transfer_unavailable__text
                }
            ),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        VerticalSpacer(24.dp)
        // Arkade keeps Spending fundable from savings or another wallet without a channel.
        if (canFundWithArkade) {
            FundingOption(
                R.string.lightning__funding__button1,
                R.drawable.ic_transfer,
                20.dp,
                "FundTransfer",
                onTransfer
            )
            FundingOption(
                R.string.lightning__funding__button2,
                R.drawable.ic_qr_purple,
                13.75.dp,
                "FundReceive",
                onFund
            )
        }
        VerticalSpacer(8.dp)
        RectangleButton(
            label = stringResource(R.string.lightning__funding_advanced__button2),
            icon = R.drawable.ic_pencil_full,
            iconTint = Colors.Purple,
            iconSize = 13.37.dp,
            onClick = onManual,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("FundManual")
        )
        if (!canFundWithArkade) {
            FundingOption(R.string.lightning__funding__button1, R.drawable.ic_transfer, 20.dp, "FundTransfer")
        }
        FundingOption(
            R.string.lightning__funding__button_hw,
            R.drawable.ic_device_tablet_speaker,
            16.25.dp,
            "FundHardware"
        )
        if (!canFundWithArkade) {
            FundingOption(R.string.lightning__funding__button2, R.drawable.ic_qr_purple, 13.75.dp, "FundReceive")
        }
        FillHeight()
        SecondaryButton(
            text = stringResource(R.string.common__learn_more),
            onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Env.BLOCKTANK_SUNSET_URL.toUri())) }
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag("FundLearnMore")
        )
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun FundingOption(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    iconSize: Dp,
    testTag: String,
    onClick: (() -> Unit)? = null,
) {
    VerticalSpacer(8.dp)
    RectangleButton(
        label = stringResource(label),
        icon = icon,
        iconTint = Colors.Purple,
        iconSize = iconSize,
        enabled = onClick != null,
        onClick = { onClick?.invoke() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(testTag)
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        FundingScreen()
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewArkade() {
    AppThemeSurface {
        FundingScreen(canFundWithArkade = true)
    }
}
