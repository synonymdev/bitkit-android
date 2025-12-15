package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.Language
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.TransactionSpeed
import to.bitkit.models.transactionSpeedUiText
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.Routes
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToDefaultUnitSettings
import to.bitkit.ui.navigateToLanguageSettings
import to.bitkit.ui.navigateToLocalCurrencySettings
import to.bitkit.ui.navigateToQuickPaySettings
import to.bitkit.ui.navigateToTagsSettings
import to.bitkit.ui.navigateToTransactionSpeedSettings
import to.bitkit.ui.navigateToWidgetsSettings
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.LanguageViewModel

@Composable
fun GeneralSettingsScreen(
    navController: NavController,
    languageViewModel: LanguageViewModel = hiltViewModel(),
) {
    val settings = settingsViewModel ?: return
    val currencies = LocalCurrencies.current
    val defaultTransactionSpeed by settings.defaultTransactionSpeed.collectAsStateWithLifecycle()
    val lastUsedTags by settings.lastUsedTags.collectAsStateWithLifecycle()
    val quickPayIntroSeen by settings.quickPayIntroSeen.collectAsStateWithLifecycle()
    val bgPaymentsIntroSeen by settings.bgPaymentsIntroSeen.collectAsStateWithLifecycle()
    val notificationsGranted by settings.notificationsGranted.collectAsStateWithLifecycle()
    val languageUiState by languageViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { languageViewModel.fetchLanguageInfo() }

    GeneralSettingsContent(
        selectedCurrency = currencies.selectedCurrency,
        primaryDisplay = currencies.primaryDisplay,
        defaultTransactionSpeed = defaultTransactionSpeed,
        showTagsButton = lastUsedTags.isNotEmpty(),
        onBackClick = { navController.popBackStack() },
        onLocalCurrencyClick = { navController.navigateToLocalCurrencySettings() },
        onDefaultUnitClick = { navController.navigateToDefaultUnitSettings() },
        onTransactionSpeedClick = { navController.navigateToTransactionSpeedSettings() },
        onWidgetsClick = { navController.navigateToWidgetsSettings() },
        onQuickPayClick = { navController.navigateToQuickPaySettings(quickPayIntroSeen) },
        onTagsClick = { navController.navigateToTagsSettings() },
        onLanguageSettingsClick = { navController.navigateToLanguageSettings() },
        onBgPaymentsClick = {
            if (bgPaymentsIntroSeen || notificationsGranted) {
                navController.navigate(Routes.BackgroundPaymentsSettings)
            } else {
                navController.navigate(Routes.BackgroundPaymentsIntro)
            }
        },
        selectedLanguage = languageUiState.selectedLanguage.displayName,
        notificationsGranted = notificationsGranted
    )
}

@Composable
private fun GeneralSettingsContent(
    selectedCurrency: String,
    primaryDisplay: PrimaryDisplay,
    defaultTransactionSpeed: TransactionSpeed,
    selectedLanguage: String,
    notificationsGranted: Boolean,
    showTagsButton: Boolean = false,
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onDefaultUnitClick: () -> Unit = {},
    onTransactionSpeedClick: () -> Unit = {},
    onWidgetsClick: () -> Unit = {},
    onQuickPayClick: () -> Unit = {},
    onLanguageSettingsClick: () -> Unit = {},
    onTagsClick: () -> Unit = {},
    onBgPaymentsClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__general_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow(
                title = "Language",
                value = SettingsButtonValue.StringValue(selectedLanguage),
                onClick = onLanguageSettingsClick,
                modifier = Modifier.testTag("LanguageSettings")
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__currency_local),
                value = SettingsButtonValue.StringValue(selectedCurrency),
                onClick = onLocalCurrencyClick,
                modifier = Modifier.testTag("CurrenciesSettings")
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__unit),
                value = SettingsButtonValue.StringValue(
                    when (primaryDisplay) {
                        PrimaryDisplay.BITCOIN -> stringResource(R.string.settings__general__unit_bitcoin)
                        PrimaryDisplay.FIAT -> selectedCurrency
                    }
                ),
                onClick = onDefaultUnitClick,
                modifier = Modifier.testTag("UnitSettings")
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__general__speed),
                value = SettingsButtonValue.StringValue(defaultTransactionSpeed.transactionSpeedUiText()),
                onClick = onTransactionSpeedClick,
                modifier = Modifier.testTag("TransactionSpeedSettings")
            )
            if (showTagsButton) {
                SettingsButtonRow(
                    title = stringResource(R.string.settings__general__tags),
                    onClick = onTagsClick,
                    modifier = Modifier.testTag("TagsSettings")
                )
            }
            SettingsButtonRow(
                title = stringResource(R.string.settings__widgets__nav_title),
                onClick = onWidgetsClick,
                modifier = Modifier.testTag("WidgetsSettings")
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__quickpay__nav_title),
                onClick = onQuickPayClick,
                modifier = Modifier.testTag("QuickpaySettings")
            )
            SettingsButtonRow(
                title = "Background Payments", // TODO Transifex
                onClick = onBgPaymentsClick,
                value = SettingsButtonValue.StringValue(if (notificationsGranted) "On" else "Off"),
                modifier = Modifier.testTag("BackgroundPaymentSettings")
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        GeneralSettingsContent(
            selectedCurrency = "USD",
            primaryDisplay = PrimaryDisplay.BITCOIN,
            defaultTransactionSpeed = TransactionSpeed.Medium,
            selectedLanguage = Language.SYSTEM_DEFAULT.displayName,
            notificationsGranted = true,
        )
    }
}
