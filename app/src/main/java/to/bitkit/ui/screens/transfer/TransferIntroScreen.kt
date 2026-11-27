package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun TransferIntroScreen(
    onContinueClick: () -> Unit = {},
    onBackClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        IntroArtLayout(
            art = R.drawable.lightning,
            title = stringResource(R.string.lightning__transfer_intro__title).withAccent(accentColor = Colors.Purple),
            text = stringResource(R.string.lightning__transfer_intro__text_spark),
            buttonText = stringResource(R.string.lightning__transfer_intro__button),
            buttonTestTag = "TransferIntro-button",
            onButtonClick = onContinueClick,
        )
        AppTopBar(
            titleText = stringResource(R.string.lightning__funding__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        TransferIntroScreen(onBackClick = {})
    }
}
