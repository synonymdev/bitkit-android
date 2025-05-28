package to.bitkit.ui.settings.quickPay

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.StepSlider
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SettingsViewModel


@Composable
fun QuickPaySettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val isQuickPayEnabled by settingsViewModel.isQuickpayEnabled.collectAsStateWithLifecycle()
    val quickPayAmount by settingsViewModel.quickPayAmount.collectAsStateWithLifecycle()

    QuickPaySettingsScreenContent(
        isQuickPayEnabled = isQuickPayEnabled,
        quickPayAmount = quickPayAmount,
        onToggleQuickPay = settingsViewModel::setIsQuickPayEnabled,
        onQuickPayAmountChange = settingsViewModel::setQuickPayAmount,
        onBack = onBack,
        onClose = onClose,
    )
}

@Composable
private fun QuickPaySettingsScreenContent(
    isQuickPayEnabled: Boolean,
    quickPayAmount: Int,
    onToggleQuickPay: (Boolean) -> Unit = {},
    onQuickPayAmountChange: (Int) -> Unit = {},
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val sliderSteps = remember { listOf(1, 5, 10, 20, 50) }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__quickpay__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            SettingsSwitchRow(
                title = stringResource(R.string.settings__quickpay__settings__toggle),
                isChecked = isQuickPayEnabled,
                onClick = { onToggleQuickPay(!isQuickPayEnabled) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            BodyM(
                text = stringResource(R.string.settings__quickpay__settings__text)
                    .replace("{amount}", quickPayAmount.toString()),
                color = Colors.White64
            )

            Spacer(modifier = Modifier.height(32.dp))

            Caption13Up(
                text = stringResource(R.string.settings__quickpay__settings__label),
                color = Colors.White64
            )

            Spacer(modifier = Modifier.height(16.dp))

            StepSlider(
                value = quickPayAmount,
                steps = sliderSteps,
                onValueChange = onQuickPayAmountChange,
            )

            Spacer(modifier = Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.fast_forward),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(256.dp)
            )
            Spacer(modifier = Modifier.weight(1f))

            BodyS(
                text = stringResource(R.string.settings__quickpay__settings__note),
                color = Colors.White64
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        QuickPaySettingsScreenContent(
            isQuickPayEnabled = true,
            quickPayAmount = 5,
        )
    }
}
