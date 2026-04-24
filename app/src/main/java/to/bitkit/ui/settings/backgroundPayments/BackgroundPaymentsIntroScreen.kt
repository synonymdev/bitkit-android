package to.bitkit.ui.settings.backgroundPayments

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.SettingsViewModel

@Composable
fun BackgroundPaymentsIntroScreen(
    onBack: () -> Unit,
    onLater: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    Column(
        modifier = modifier.screen()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.settings__bg__title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        BackgroundPaymentsIntroContent(
            onLater = {
                settingsViewModel.setBgPaymentsIntroSeen(true)
                onLater()
            },
            onEnable = {
                settingsViewModel.setBgPaymentsIntroSeen(true)
                onEnable()
            },
        )
    }
}

@Composable
fun BackgroundPaymentsIntroContent(
    onLater: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.bell),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Display(
            text = stringResource(R.string.settings__bg__intro_title).withAccent(accentColor = Colors.Purple),
            color = Colors.White,
        )
        VerticalSpacer(8.dp)
        BodyM(text = stringResource(R.string.settings__bg__intro_desc), color = Colors.White64)
        VerticalSpacer(32.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("BackgroundPaymentsIntro-buttons")
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__later),
                fullWidth = false,
                onClick = onLater,
                modifier = Modifier
                    .weight(1f)
                    .testTag("BackgroundPaymentsIntro-later")
            )
            PrimaryButton(
                text = stringResource(R.string.settings__bg__intro_button),
                fullWidth = false,
                onClick = onEnable,
                modifier = Modifier
                    .weight(1f)
                    .testTag("BackgroundPaymentsIntro-enable")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BackgroundPaymentsIntroContent(
            onLater = {},
            onEnable = {},
        )
    }
}
