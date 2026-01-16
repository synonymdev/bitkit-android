package to.bitkit.ui.screens.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ComingSoonScreen(
    onWalletOverviewClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .screen()
            .testTag("ComingSoonScreen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.coming_soon__title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.img_cronometer),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Display(
                text = stringResource(R.string.coming_soon__headline).withAccent(accentColor = Colors.Brand),
                color = Colors.White,
            )
            VerticalSpacer(8.dp)
            BodyM(text = stringResource(R.string.coming_soon__description), color = Colors.White64)
            VerticalSpacer(54.dp)
            PrimaryButton(
                text = stringResource(R.string.coming_soon__button),
                onClick = onWalletOverviewClick,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ComingSoonScreen(
            onWalletOverviewClick = {},
            onBackClick = {}
        )
    }
}
