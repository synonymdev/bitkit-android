package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun WalletRestoreSuccessView(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier.padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(24.dp)

        Column {
            Display(
                stringResource(R.string.onboarding__restore_success_header).withAccent(accentColor = Colors.Green)
            )
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.onboarding__restore_success_text), color = Colors.White80)
        }

        FillHeight()

        Image(
            painter = painterResource(R.drawable.check),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally)
        )

        FillHeight()

        PrimaryButton(
            text = stringResource(R.string.onboarding__get_started),
            onClick = onContinue,
            modifier = Modifier.testTag("GetStartedButton")
        )

        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WalletRestoreSuccessView(
            onContinue = {},
        )
    }
}
