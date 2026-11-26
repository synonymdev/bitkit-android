package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SpendingIntroScreen(
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .screen()
    ) {
        IntroArtLayout(
            art = R.drawable.coin_stack_x,
            title = stringResource(R.string.lightning__spending_intro__title).withAccent(accentColor = Colors.Purple),
            text = stringResource(R.string.lightning__spending_intro__text),
            buttonText = stringResource(R.string.lightning__spending_intro__button),
            buttonTestTag = "SpendingIntro-button",
            onButtonClick = onContinueClick,
        )
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun SpendingIntroScreenPreview() {
    AppThemeSurface {
        SpendingIntroScreen()
    }
}
