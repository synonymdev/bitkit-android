package to.bitkit.ui.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import to.bitkit.ui.NodeInfoScreen
import to.bitkit.ui.components.AuthCheckScreen
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.screens.settings.DevSettingsScreen
import to.bitkit.ui.screens.settings.FeeSettingsScreen
import to.bitkit.ui.screens.settings.LdkDebugScreen
import to.bitkit.ui.settings.AboutScreen
import to.bitkit.ui.settings.AdvancedSettingsScreen
import to.bitkit.ui.settings.BackupSettingsScreen
import to.bitkit.ui.settings.BlocktankRegtestScreen
import to.bitkit.ui.settings.CJitDetailScreen
import to.bitkit.ui.settings.ChannelOrdersScreen
import to.bitkit.ui.settings.LanguageSettingsScreen
import to.bitkit.ui.settings.LogDetailScreen
import to.bitkit.ui.settings.LogsScreen
import to.bitkit.ui.settings.OrderDetailScreen
import to.bitkit.ui.settings.SecuritySettingsScreen
import to.bitkit.ui.settings.SettingsScreen
import to.bitkit.ui.settings.advanced.AddressViewerScreen
import to.bitkit.ui.settings.advanced.CoinSelectPreferenceScreen
import to.bitkit.ui.settings.advanced.ElectrumConfigScreen
import to.bitkit.ui.settings.advanced.RgsServerScreen
import to.bitkit.ui.settings.appStatus.AppStatusScreen
import to.bitkit.ui.settings.backgroundPayments.BackgroundPaymentsIntroScreen
import to.bitkit.ui.settings.backgroundPayments.BackgroundPaymentsSettings
import to.bitkit.ui.settings.backups.ResetAndRestoreScreen
import to.bitkit.ui.settings.general.DefaultUnitSettingsScreen
import to.bitkit.ui.settings.general.GeneralSettingsScreen
import to.bitkit.ui.settings.general.LocalCurrencySettingsScreen
import to.bitkit.ui.settings.general.TagsSettingsScreen
import to.bitkit.ui.settings.general.WidgetsSettingsScreen
import to.bitkit.ui.settings.lightning.ChannelDetailScreen
import to.bitkit.ui.settings.lightning.CloseConnectionScreen
import to.bitkit.ui.settings.lightning.LightningConnectionsScreen
import to.bitkit.ui.settings.lightning.LightningConnectionsViewModel
import to.bitkit.ui.settings.pin.ChangePinConfirmScreen
import to.bitkit.ui.settings.pin.ChangePinNewScreen
import to.bitkit.ui.settings.pin.ChangePinResultScreen
import to.bitkit.ui.settings.pin.ChangePinScreen
import to.bitkit.ui.settings.pin.DisablePinScreen
import to.bitkit.ui.settings.quickPay.QuickPayIntroScreen
import to.bitkit.ui.settings.quickPay.QuickPaySettingsScreen
import to.bitkit.ui.settings.support.ReportIssueResultScreen
import to.bitkit.ui.settings.support.ReportIssueScreen
import to.bitkit.ui.settings.support.SupportScreen
import to.bitkit.ui.settings.transactionSpeed.CustomFeeSettingsScreen
import to.bitkit.ui.settings.transactionSpeed.TransactionSpeedSettingsScreen
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.SettingsViewModel

/**
 * Settings section entry providers for Navigation 3.
 */
@Suppress("LongMethod", "LongParameterList")
fun EntryProviderScope<NavKey>.settingsEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
    currencyViewModel: CurrencyViewModel,
    lightningConnectionsViewModel: LightningConnectionsViewModel,
) {
    entry<Routes.Settings> {
        SettingsScreen(navigator)
    }

    entry<Routes.GeneralSettings> {
        GeneralSettingsScreen(navigator)
    }

    entry<Routes.SecuritySettings> {
        SecuritySettingsScreen(navigator)
    }

    entry<Routes.AdvancedSettings> {
        AdvancedSettingsScreen(navigator)
    }

    entry<Routes.AboutSettings> {
        AboutScreen(onBack = { navigator.goBack() })
    }

    entry<Routes.TransactionSpeedSettings> {
        TransactionSpeedSettingsScreen(navigator)
    }

    entry<Routes.CustomFeeSettings> {
        CustomFeeSettingsScreen(navigator)
    }

    entry<Routes.WidgetsSettings> {
        WidgetsSettingsScreen(navigator)
    }

    entry<Routes.TagsSettings> {
        TagsSettingsScreen(navigator)
    }

    entry<Routes.NodeInfo> {
        NodeInfoScreen(navigator)
    }

    entry<Routes.CoinSelectPreference> {
        CoinSelectPreferenceScreen(navigator)
    }

    entry<Routes.ElectrumConfig> {
        ElectrumConfigScreen(navigator)
    }

    entry<Routes.RgsServer> {
        RgsServerScreen(navigator)
    }

    entry<Routes.AddressViewer> {
        AddressViewerScreen(navigator)
    }

    entry<Routes.DisablePin> {
        DisablePinScreen(navigator)
    }

    entry<Routes.ChangePin> {
        ChangePinScreen(navigator)
    }

    entry<Routes.ChangePinNew> {
        ChangePinNewScreen(navigator)
    }

    entry<Routes.ChangePinConfirm> { route ->
        ChangePinConfirmScreen(newPin = route.newPin, navigator = navigator)
    }

    entry<Routes.ChangePinResult> {
        ChangePinResultScreen(navigator)
    }

    entry<Routes.AuthCheck> { route ->
        AuthCheckScreen(
            navigator = navigator,
            route = route,
            appViewModel = appViewModel,
            settingsViewModel = settingsViewModel,
        )
    }

    entry<Routes.DefaultUnitSettings> {
        DefaultUnitSettingsScreen(currencyViewModel = currencyViewModel, navigator = navigator)
    }

    entry<Routes.LocalCurrencySettings> {
        LocalCurrencySettingsScreen(currencyViewModel = currencyViewModel, navigator = navigator)
    }

    entry<Routes.BackupSettings> {
        BackupSettingsScreen(navigator)
    }

    entry<Routes.ResetAndRestoreSettings> {
        ResetAndRestoreScreen(navigator)
    }

    entry<Routes.ChannelOrdersSettings> {
        ChannelOrdersScreen(
            onBackClick = { navigator.goBack() },
            onOrderItemClick = { orderId -> navigator.navigate(Routes.OrderDetail(orderId)) },
            onCjitItemClick = { entryId -> navigator.navigate(Routes.CjitDetail(entryId)) },
        )
    }

    entry<Routes.OrderDetail> { route ->
        OrderDetailScreen(
            orderId = route.orderId,
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.CjitDetail> { route ->
        CJitDetailScreen(
            entryId = route.entryId,
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.LightningConnections> {
        LightningConnectionsScreen(navigator = navigator, viewModel = lightningConnectionsViewModel)
    }

    entry<Routes.ChannelDetail> {
        ChannelDetailScreen(navigator = navigator, viewModel = lightningConnectionsViewModel)
    }

    entry<Routes.CloseConnection> {
        CloseConnectionScreen(navigator = navigator, viewModel = lightningConnectionsViewModel)
    }

    entry<Routes.Logs> {
        LogsScreen(navigator)
    }

    entry<Routes.LogDetail> { route ->
        LogDetailScreen(navigator = navigator, fileName = route.fileName)
    }

    entry<Routes.QuickPayIntro> {
        QuickPayIntroScreen(
            onBack = { navigator.goBack() },
            onContinue = { navigator.navigate(Routes.QuickPaySettings) },
        )
    }

    entry<Routes.QuickPaySettings> {
        QuickPaySettingsScreen(onBack = { navigator.goBack() })
    }

    entry<Routes.LanguageSettings> {
        LanguageSettingsScreen(onBackClick = { navigator.goBack() })
    }

    entry<Routes.BackgroundPaymentsIntro> {
        BackgroundPaymentsIntroScreen(
            onBack = { navigator.goBack() },
            onContinue = { navigator.navigate(Routes.BackgroundPaymentsSettings) },
        )
    }

    entry<Routes.BackgroundPaymentsSettings> {
        BackgroundPaymentsSettings(onBack = { navigator.goBack() })
    }

    entry<Routes.DevSettings> {
        DevSettingsScreen(navigator)
    }

    entry<Routes.LdkDebug> {
        LdkDebugScreen(navigator)
    }

    entry<Routes.FeeSettings> {
        FeeSettingsScreen(navigator)
    }

    entry<Routes.RegtestSettings> {
        BlocktankRegtestScreen(navigator)
    }

    entry<Routes.AppStatus> {
        AppStatusScreen(navigator)
    }

    entry<Routes.Support> {
        SupportScreen(navigator)
    }

    entry<Routes.ReportIssue> {
        ReportIssueScreen(
            onBack = { navigator.goBack() },
            navigateResultScreen = { success ->
                if (success) {
                    navigator.navigate(Routes.ReportIssueSuccess)
                } else {
                    navigator.navigate(Routes.ReportIssueFailure)
                }
            }
        )
    }

    entry<Routes.ReportIssueSuccess> {
        ReportIssueResultScreen(
            isSuccess = true,
            onBack = { navigator.goBack() },
            onClose = { navigator.navigate(Routes.Support) },
        )
    }

    entry<Routes.ReportIssueFailure> {
        ReportIssueResultScreen(
            isSuccess = false,
            onBack = { navigator.goBack() },
            onClose = { navigator.navigate(Routes.Support) },
        )
    }
}
