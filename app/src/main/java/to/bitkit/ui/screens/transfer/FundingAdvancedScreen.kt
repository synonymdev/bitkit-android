package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun FundingAdvancedScreen(
    onLnurl: () -> Unit = {},
    onManual: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__funding_advanced__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(32.dp)
            Display(
                text = stringResource(
                    R.string.lightning__funding_advanced__title
                ).withAccent(accentColor = Colors.Purple)
            )
            VerticalSpacer(8.dp)
            BodyM(text = stringResource(R.string.lightning__funding_advanced__text), color = Colors.White64)
            VerticalSpacer(32.dp)

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RectangleButton(
                    label = stringResource(R.string.lightning__funding_advanced__button1),
                    icon = R.drawable.ic_scan,
                    iconTint = Colors.Purple,
                    iconSize = 13.75.dp,
                    onClick = onLnurl,
                )
                RectangleButton(
                    label = stringResource(R.string.lightning__funding_advanced__button2),
                    icon = R.drawable.ic_pencil_full,
                    iconTint = Colors.Purple,
                    iconSize = 13.37.dp,
                    onClick = onManual,
                    modifier = Modifier.testTag("FundManual")
                )
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun FundingAdvancedScreenPreview() {
    AppThemeSurface {
        FundingAdvancedScreen()
    }
}
