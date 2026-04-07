package to.bitkit.ui.screens.transfer.external

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.InfoScreenContent
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.localizedRandom
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

@Composable
fun ExternalSuccessScreen(
    onContinue: () -> Unit,
) {
    InfoScreenContent(
        navTitle = stringResource(R.string.lightning__external_success__nav_title),
        title = stringResource(R.string.lightning__external_success__title).withAccent(accentColor = Colors.Purple),
        description = stringResource(R.string.lightning__external_success__text).withAccentBoldBright(),
        image = painterResource(R.drawable.lightning),
        buttonText = localizedRandom(R.string.common__ok_random),
        onButtonClick = onContinue,
        testTag = "ExternalSuccess",
    )
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun ExternalSuccessScreenPreview() {
    AppThemeSurface {
        ExternalSuccessScreen(
            onContinue = {},
        )
    }
}
