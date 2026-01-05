package to.bitkit.ui.nav.entries

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import to.bitkit.models.Toast
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.transfer.FundingAdvancedScreen
import to.bitkit.ui.screens.transfer.FundingScreen
import to.bitkit.ui.screens.transfer.LiquidityScreen
import to.bitkit.ui.screens.transfer.SavingsAdvancedScreen
import to.bitkit.ui.screens.transfer.SavingsAvailabilityScreen
import to.bitkit.ui.screens.transfer.SavingsConfirmScreen
import to.bitkit.ui.screens.transfer.SavingsIntroScreen
import to.bitkit.ui.screens.transfer.SavingsProgressScreen
import to.bitkit.ui.screens.transfer.SettingUpScreen
import to.bitkit.ui.screens.transfer.SpendingAdvancedScreen
import to.bitkit.ui.screens.transfer.SpendingAmountScreen
import to.bitkit.ui.screens.transfer.SpendingConfirmScreen
import to.bitkit.ui.screens.transfer.SpendingIntroScreen
import to.bitkit.ui.screens.transfer.TransferIntroScreen
import to.bitkit.ui.screens.transfer.external.ExternalAmountScreen
import to.bitkit.ui.screens.transfer.external.ExternalConfirmScreen
import to.bitkit.ui.screens.transfer.external.ExternalConnectionScreen
import to.bitkit.ui.screens.transfer.external.ExternalFeeCustomScreen
import to.bitkit.ui.screens.transfer.external.ExternalNodeViewModel
import to.bitkit.ui.screens.transfer.external.ExternalSuccessScreen
import to.bitkit.ui.screens.transfer.external.LnurlChannelScreen
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

@Suppress("LongMethod", "LongParameterList")
fun EntryProviderScope<NavKey>.transferEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    transferViewModel: TransferViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.Transfer.Intro> {
        TransferIntroScreen(
            onContinueClick = {
                navigator.navigate(Routes.Transfer.Funding)
                settingsViewModel.setHasSeenTransferIntro(true)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.ToSavings.Intro> {
        SavingsIntroScreen(
            onContinueClick = {
                navigator.navigate(Routes.Transfer.ToSavings.Availability)
                settingsViewModel.setHasSeenSavingsIntro(true)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.ToSavings.Availability> {
        SavingsAvailabilityScreen(
            onBackClick = { navigator.goBack() },
            onCancelClick = { navigator.navigateHome() },
            onContinueClick = { navigator.navigate(Routes.Transfer.ToSavings.Confirm) },
        )
    }

    entry<Routes.Transfer.ToSavings.Confirm> {
        SavingsConfirmScreen(
            onConfirm = { navigator.navigate(Routes.Transfer.ToSavings.Progress) },
            onAdvancedClick = { navigator.navigate(Routes.Transfer.ToSavings.Advanced) },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.ToSavings.Advanced> {
        SavingsAdvancedScreen(
            onContinueClick = { navigator.goBack() },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.ToSavings.Progress> {
        SavingsProgressScreen(
            wallet = walletViewModel,
            transfer = transferViewModel,
            onContinueClick = { navigator.navigateHome() },
            onForceTransfer = { navigator.navigate(Routes.Sheet.ForceTransfer) },
        )
    }

    entry<Routes.Transfer.ToSpending.Intro> {
        SpendingIntroScreen(
            onContinueClick = {
                navigator.navigate(Routes.Transfer.ToSpending.Amount)
                settingsViewModel.setHasSeenSpendingIntro(true)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.ToSpending.Amount> {
        SpendingAmountScreen(
            viewModel = transferViewModel,
            onBackClick = { navigator.goBack() },
            onOrderCreated = { navigator.navigate(Routes.Transfer.ToSpending.Confirm) },
            toastException = { appViewModel.toast(it) },
            toast = { title, description ->
                appViewModel.toast(
                    type = Toast.ToastType.ERROR,
                    title = title,
                    description = description,
                )
            },
        )
    }

    entry<Routes.Transfer.ToSpending.Confirm> {
        SpendingConfirmScreen(
            viewModel = transferViewModel,
            onBackClick = { navigator.goBack() },
            onCloseClick = { navigator.navigateHome() },
            onLearnMoreClick = { navigator.navigate(Routes.Transfer.Liquidity) },
            onAdvancedClick = { navigator.navigate(Routes.Transfer.ToSpending.Advanced) },
            onConfirm = { navigator.navigate(Routes.Transfer.SettingUp) },
        )
    }

    entry<Routes.Transfer.ToSpending.Advanced> {
        SpendingAdvancedScreen(
            viewModel = transferViewModel,
            onBackClick = { navigator.goBack() },
            onOrderCreated = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.Liquidity> {
        LiquidityScreen(
            onBackClick = { navigator.goBack() },
            onContinueClick = { navigator.goBack() },
        )
    }

    entry<Routes.Transfer.SettingUp> {
        SettingUpScreen(
            viewModel = transferViewModel,
            onContinueClick = { navigator.navigateHome() },
        )
    }

    entry<Routes.Transfer.Funding> {
        FundingEntry(
            navigator = navigator,
            walletViewModel = walletViewModel,
            appViewModel = appViewModel,
            settingsViewModel = settingsViewModel,
        )
    }

    entry<Routes.Transfer.FundingAdvanced> {
        FundingAdvancedScreen(
            onLnurl = { navigator.navigate(Routes.Scanner) },
            onManual = { navigator.navigate(Routes.External.Connection()) },
            onBackClick = { navigator.goBack() },
        )
    }

    externalNodeEntries(
        navigator = navigator,
        walletViewModel = walletViewModel,
    )
}

@Composable
private fun FundingEntry(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
    val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()

    FundingScreen(
        onTransfer = {
            if (!hasSeenSpendingIntro) {
                navigator.navigate(Routes.Transfer.ToSpending.Intro)
            } else {
                navigator.navigate(Routes.Transfer.ToSpending.Amount)
            }
        },
        onFund = {
            walletViewModel.resetReceiveState()
            navigator.navigate(Routes.Receive.Main)
        },
        onAdvanced = { navigator.navigate(Routes.Transfer.FundingAdvanced) },
        onBackClick = { navigator.goBack() },
        isGeoBlocked = isGeoBlocked,
    )
}

/**
 * External node connection flow entries.
 * Note: Uses a shared ViewModel across screens for the external node connection flow.
 */
private fun EntryProviderScope<NavKey>.externalNodeEntries(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
) {
    entry<Routes.External.Connection> { route ->
        ExternalConnectionEntry(
            navigator = navigator,
            scannedNodeUri = route.scannedNodeUri,
        )
    }

    entry<Routes.External.Amount> {
        ExternalAmountEntry(navigator = navigator)
    }

    entry<Routes.External.Confirm> {
        ExternalConfirmEntry(
            navigator = navigator,
            walletViewModel = walletViewModel,
        )
    }

    entry<Routes.External.FeeCustom> {
        ExternalFeeCustomEntry(navigator = navigator)
    }

    entry<Routes.External.Success> {
        ExternalSuccessScreen(
            onContinue = { navigator.navigateHome() },
        )
    }

    entry<Routes.Sheet.LnurlChannel> { route ->
        LnurlChannelScreen(
            uri = route.uri,
            callback = route.callback,
            k1 = route.k1,
            onConnected = { navigator.navigate(Routes.External.Success) },
            onBack = { navigator.goBack() },
            onClose = { navigator.navigateHome() },
        )
    }

    entry<Routes.External.NodeScanner> {
        QrScanningScreen(
            navigator = navigator,
            onScanSuccess = { qrCode ->
                navigator.navigate(Routes.External.Connection(scannedNodeUri = qrCode))
            },
        )
    }
}

@Composable
private fun ExternalConnectionEntry(
    navigator: Navigator,
    scannedNodeUri: String?,
    viewModel: ExternalNodeViewModel = hiltViewModel(),
) {
    ExternalConnectionScreen(
        scannedNodeUri = scannedNodeUri,
        viewModel = viewModel,
        onNodeConnected = { navigator.navigate(Routes.External.Amount) },
        onScanClick = { navigator.navigate(Routes.External.NodeScanner) },
        onBackClick = { navigator.goBack() },
    )
}

@Composable
private fun ExternalAmountEntry(
    navigator: Navigator,
    viewModel: ExternalNodeViewModel = hiltViewModel(),
) {
    ExternalAmountScreen(
        viewModel = viewModel,
        onContinue = { navigator.navigate(Routes.External.Confirm) },
        onBackClick = { navigator.goBack() },
    )
}

@Composable
private fun ExternalConfirmEntry(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
    viewModel: ExternalNodeViewModel = hiltViewModel(),
) {
    ExternalConfirmScreen(
        viewModel = viewModel,
        onConfirm = {
            walletViewModel.refreshState()
            navigator.navigate(Routes.External.Success)
        },
        onNetworkFeeClick = { navigator.navigate(Routes.External.FeeCustom) },
        onBackClick = { navigator.goBack() },
    )
}

@Composable
private fun ExternalFeeCustomEntry(
    navigator: Navigator,
    viewModel: ExternalNodeViewModel = hiltViewModel(),
) {
    ExternalFeeCustomScreen(
        viewModel = viewModel,
        onBack = { navigator.goBack() },
    )
}
