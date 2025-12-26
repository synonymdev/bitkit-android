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

@Suppress("LongMethod", "LongParameterList")
fun EntryProviderScope<NavKey>.settingsEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
    currencyViewModel: CurrencyViewModel,
    lightningConnectionsViewModel: LightningConnectionsViewModel,
) {
    entry<Routes.Settings.Main> {
        SettingsScreen(navigator)
    }

    entry<Routes.Settings.General> {
        GeneralSettingsScreen(navigator)
    }

    entry<Routes.Settings.Security> {
        SecuritySettingsScreen(navigator)
    }

    entry<Routes.Settings.Advanced> {
        AdvancedSettingsScreen(navigator)
    }

    entry<Routes.Settings.About> {
        AboutScreen(onBack = { navigator.goBack() })
    }

    entry<Routes.Settings.TransactionSpeed> {
        TransactionSpeedSettingsScreen(navigator)
    }

    entry<Routes.Settings.CustomFee> {
        CustomFeeSettingsScreen(navigator)
    }

    entry<Routes.Settings.Widgets> {
        WidgetsSettingsScreen(navigator)
    }

    entry<Routes.Settings.Tags> {
        TagsSettingsScreen(navigator)
    }

    entry<Routes.Settings.NodeInfo> {
        NodeInfoScreen(navigator)
    }

    entry<Routes.Settings.CoinSelectPreference> {
        CoinSelectPreferenceScreen(navigator)
    }

    entry<Routes.Settings.ElectrumConfig> {
        ElectrumConfigScreen(navigator)
    }

    entry<Routes.Settings.RgsServer> {
        RgsServerScreen(navigator)
    }

    entry<Routes.Settings.AddressViewer> {
        AddressViewerScreen(navigator)
    }

    entry<Routes.Settings.DisablePin> {
        DisablePinScreen(navigator)
    }

    entry<Routes.ChangePin.Start> {
        ChangePinScreen(navigator)
    }

    entry<Routes.ChangePin.New> {
        ChangePinNewScreen(navigator)
    }

    entry<Routes.ChangePin.Confirm> { route ->
        ChangePinConfirmScreen(newPin = route.newPin, navigator = navigator)
    }

    entry<Routes.ChangePin.Result> {
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

    entry<Routes.Settings.DefaultUnit> {
        DefaultUnitSettingsScreen(currencyViewModel = currencyViewModel, navigator = navigator)
    }

    entry<Routes.Settings.LocalCurrency> {
        LocalCurrencySettingsScreen(currencyViewModel = currencyViewModel, navigator = navigator)
    }

    entry<Routes.Settings.BackupSettings> {
        BackupSettingsScreen(navigator)
    }

    entry<Routes.Settings.ResetAndRestore> {
        ResetAndRestoreScreen(navigator)
    }

    entry<Routes.Settings.Dev.ChannelOrders> {
        ChannelOrdersScreen(
            onBackClick = { navigator.goBack() },
            onOrderItemClick = { orderId -> navigator.navigate(Routes.Settings.Dev.OrderDetail(orderId)) },
            onCjitItemClick = { entryId -> navigator.navigate(Routes.Settings.Dev.CjitDetail(entryId)) },
        )
    }

    entry<Routes.Settings.Dev.OrderDetail> { route ->
        OrderDetailScreen(
            orderId = route.orderId,
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Settings.Dev.CjitDetail> { route ->
        CJitDetailScreen(
            entryId = route.entryId,
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Settings.LightningConnections> {
        LightningConnectionsScreen(navigator = navigator, viewModel = lightningConnectionsViewModel)
    }

    entry<Routes.Settings.ChannelDetail> {
        ChannelDetailScreen(navigator = navigator, viewModel = lightningConnectionsViewModel)
    }

    entry<Routes.Settings.CloseConnection> {
        CloseConnectionScreen(navigator = navigator, viewModel = lightningConnectionsViewModel)
    }

    entry<Routes.Settings.Dev.Log.List> {
        LogsScreen(navigator)
    }

    entry<Routes.Settings.Dev.Log.Detail> { route ->
        LogDetailScreen(navigator = navigator, fileName = route.fileName)
    }

    entry<Routes.QuickPay.Intro> {
        QuickPayIntroScreen(
            onBack = { navigator.goBack() },
            onContinue = { navigator.navigate(Routes.QuickPay.Settings) },
        )
    }

    entry<Routes.QuickPay.Settings> {
        QuickPaySettingsScreen(onBack = { navigator.goBack() })
    }

    entry<Routes.Settings.Language> {
        LanguageSettingsScreen(onBackClick = { navigator.goBack() })
    }

    entry<Routes.BackgroundPayments.Intro> {
        BackgroundPaymentsIntroScreen(
            onBack = { navigator.goBack() },
            onContinue = { navigator.navigate(Routes.BackgroundPayments.Settings) },
        )
    }

    entry<Routes.BackgroundPayments.Settings> {
        BackgroundPaymentsSettings(onBack = { navigator.goBack() })
    }

    entry<Routes.Settings.Dev.Main> {
        DevSettingsScreen(navigator)
    }

    entry<Routes.Settings.Dev.LdkDebug> {
        LdkDebugScreen(navigator)
    }

    entry<Routes.Settings.Fee> {
        FeeSettingsScreen(navigator)
    }

    entry<Routes.Settings.Dev.Regtest> {
        BlocktankRegtestScreen(navigator)
    }

    entry<Routes.AppStatus> {
        AppStatusScreen(navigator)
    }

    entry<Routes.Support> {
        SupportScreen(navigator)
    }

    entry<Routes.ReportIssue.Form> {
        ReportIssueScreen(
            onBack = { navigator.goBack() },
            navigateResultScreen = { success ->
                if (success) {
                    navigator.navigate(Routes.ReportIssue.Success)
                } else {
                    navigator.navigate(Routes.ReportIssue.Failure)
                }
            }
        )
    }

    entry<Routes.ReportIssue.Success> {
        ReportIssueResultScreen(
            isSuccess = true,
            onBack = { navigator.goBack() },
            onClose = { navigator.navigate(Routes.Support) },
        )
    }

    entry<Routes.ReportIssue.Failure> {
        ReportIssueResultScreen(
            isSuccess = false,
            onBack = { navigator.goBack() },
            onClose = { navigator.navigate(Routes.Support) },
        )
    }
}
