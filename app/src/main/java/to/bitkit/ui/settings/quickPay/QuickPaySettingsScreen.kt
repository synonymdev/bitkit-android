package to.bitkit.ui.settings.quickPay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Slider
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SettingsViewModel

@Composable
fun QuickPaySettingsScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val isQuickPayEnabled by settingsViewModel.isQuickpayEnabled.collectAsStateWithLifecycle()
    val quickPayAmount by settingsViewModel.quickPayAmount.collectAsStateWithLifecycle()
    val quickPayDailyLimitMultiplier by settingsViewModel.quickPayDailyLimitMultiplier.collectAsStateWithLifecycle()

    QuickPaySettingsScreenContent(
        isQuickPayEnabled = isQuickPayEnabled,
        quickPayAmount = quickPayAmount,
        quickPayDailyLimitMultiplier = quickPayDailyLimitMultiplier,
        onToggleQuickPay = settingsViewModel::setIsQuickPayEnabled,
        onQuickPayAmountChange = settingsViewModel::setQuickPayAmount,
        onQuickPayDailyLimitMultiplierChange = settingsViewModel::setQuickPayDailyLimitMultiplier,
        onBack = onBack,
    )
}

@Composable
fun QuickPaySettingsScreenContent(
    isQuickPayEnabled: Boolean,
    quickPayAmount: Int,
    quickPayDailyLimitMultiplier: Int,
    onToggleQuickPay: (Boolean) -> Unit = {},
    onQuickPayAmountChange: (Int) -> Unit = {},
    onQuickPayDailyLimitMultiplierChange: (Int) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val sliderSteps = remember { persistentListOf(1, 5, 10, 20, 50) }
    val dailyLimitSteps = remember { persistentListOf(1, 3, 5, 10, 50) }
    val dailyLimitUsd = quickPayAmount * quickPayDailyLimitMultiplier
    val multiplierFormat = stringResource(R.string.settings__quickpay__settings__multiplier_format)

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__quickpay__nav_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)

            SettingsSwitchRow(
                title = stringResource(R.string.settings__quickpay__settings__toggle),
                isChecked = isQuickPayEnabled,
                onClick = { onToggleQuickPay(!isQuickPayEnabled) },
                modifier = Modifier.testTag("QuickpayToggle")
            )

            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.settings__quickpay__settings__text)
                    .replace("{amount}", quickPayAmount.toString()),
                color = Colors.White64,
            )

            VerticalSpacer(32.dp)

            Caption13Up(
                text = stringResource(R.string.settings__quickpay__settings__label),
                color = Colors.White64,
            )

            VerticalSpacer(16.dp)

            Slider(
                value = quickPayAmount,
                steps = sliderSteps,
                onValueChange = onQuickPayAmountChange,
                modifier = Modifier.testTag("QuickpayAmountSlider")
            )

            VerticalSpacer(32.dp)

            Caption13Up(
                text = stringResource(R.string.settings__quickpay__settings__daily_label),
                color = Colors.White64,
            )

            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.settings__quickpay__settings__daily_text)
                    .replace("{limit}", dailyLimitUsd.toString()),
                color = Colors.White64,
            )

            VerticalSpacer(16.dp)

            Slider(
                value = quickPayDailyLimitMultiplier,
                steps = dailyLimitSteps,
                onValueChange = onQuickPayDailyLimitMultiplierChange,
                formatLabel = { multiplierFormat.replace("{multiplier}", it.toString()) },
                modifier = Modifier.testTag("QuickpayDailyLimitSlider")
            )

            VerticalSpacer(32.dp)

            BodyS(
                text = stringResource(R.string.settings__quickpay__settings__note),
                color = Colors.White64,
            )

            VerticalSpacer(16.dp)
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
            quickPayDailyLimitMultiplier = 5,
        )
    }
}
