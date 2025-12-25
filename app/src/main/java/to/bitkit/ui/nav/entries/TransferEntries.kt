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

/**
 * Transfer flow entry providers for Navigation 3.
 */
@Suppress("LongMethod", "LongParameterList")
fun EntryProviderScope<NavKey>.transferEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    transferViewModel: TransferViewModel,
    settingsViewModel: SettingsViewModel,
) {
    // Transfer Intro
    entry<Routes.TransferIntro> {
        TransferIntroScreen(
            onContinueClick = {
                navigator.navigate(Routes.Funding)
                settingsViewModel.setHasSeenTransferIntro(true)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    // Savings Flow
    entry<Routes.SavingsIntro> {
        SavingsIntroScreen(
            onContinueClick = {
                navigator.navigate(Routes.SavingsAvailability)
                settingsViewModel.setHasSeenSavingsIntro(true)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.SavingsAvailability> {
        SavingsAvailabilityScreen(
            onBackClick = { navigator.goBack() },
            onCancelClick = { navigator.navigateToHome() },
            onContinueClick = { navigator.navigate(Routes.SavingsConfirm) },
        )
    }

    entry<Routes.SavingsConfirm> {
        SavingsConfirmScreen(
            onConfirm = { navigator.navigate(Routes.SavingsProgress) },
            onAdvancedClick = { navigator.navigate(Routes.SavingsAdvanced) },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.SavingsAdvanced> {
        SavingsAdvancedScreen(
            onContinueClick = { navigator.goBack() },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.SavingsProgress> {
        SavingsProgressScreen(
            wallet = walletViewModel,
            transfer = transferViewModel,
            onContinueClick = { navigator.navigateToHome() },
            onForceTransfer = { navigator.navigate(Routes.ForceTransferSheet) },
        )
    }

    // Spending Flow
    entry<Routes.SpendingIntro> {
        SpendingIntroScreen(
            onContinueClick = {
                navigator.navigate(Routes.SpendingAmount)
                settingsViewModel.setHasSeenSpendingIntro(true)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.SpendingAmount> {
        SpendingAmountScreen(
            viewModel = transferViewModel,
            onBackClick = { navigator.goBack() },
            onOrderCreated = { navigator.navigate(Routes.SpendingConfirm) },
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

    entry<Routes.SpendingConfirm> {
        SpendingConfirmScreen(
            viewModel = transferViewModel,
            onBackClick = { navigator.goBack() },
            onCloseClick = { navigator.navigateToHome() },
            onLearnMoreClick = { navigator.navigate(Routes.TransferLiquidity) },
            onAdvancedClick = { navigator.navigate(Routes.SpendingAdvanced) },
            onConfirm = { navigator.navigate(Routes.SettingUp) },
        )
    }

    entry<Routes.SpendingAdvanced> {
        SpendingAdvancedScreen(
            viewModel = transferViewModel,
            onBackClick = { navigator.goBack() },
            onOrderCreated = { navigator.goBack() },
        )
    }

    entry<Routes.TransferLiquidity> {
        LiquidityScreen(
            onBackClick = { navigator.goBack() },
            onContinueClick = { navigator.goBack() },
        )
    }

    entry<Routes.SettingUp> {
        SettingUpScreen(
            viewModel = transferViewModel,
            onContinueClick = { navigator.navigateToHome() },
        )
    }

    // Funding Flow
    entry<Routes.Funding> {
        FundingEntry(
            navigator = navigator,
            appViewModel = appViewModel,
            settingsViewModel = settingsViewModel,
        )
    }

    entry<Routes.FundingAdvanced> {
        FundingAdvancedScreen(
            onLnurl = { navigator.navigate(Routes.QrScanner) },
            onManual = { navigator.navigate(Routes.ExternalConnection()) },
            onBackClick = { navigator.goBack() },
        )
    }

    // External Node Flow
    externalNodeEntries(
        navigator = navigator,
        walletViewModel = walletViewModel,
    )
}

@Composable
private fun FundingEntry(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
    val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()

    FundingScreen(
        onTransfer = {
            if (!hasSeenSpendingIntro) {
                navigator.navigate(Routes.SpendingIntro)
            } else {
                navigator.navigate(Routes.SpendingAmount)
            }
        },
        onFund = {
            navigator.navigate(Routes.ReceiveQr)
        },
        onAdvanced = { navigator.navigate(Routes.FundingAdvanced) },
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
    entry<Routes.ExternalConnection> { route ->
        ExternalConnectionEntry(
            navigator = navigator,
            scannedNodeUri = route.scannedNodeUri,
        )
    }

    entry<Routes.ExternalAmount> {
        ExternalAmountEntry(navigator = navigator)
    }

    entry<Routes.ExternalConfirm> {
        ExternalConfirmEntry(
            navigator = navigator,
            walletViewModel = walletViewModel,
        )
    }

    entry<Routes.ExternalFeeCustom> {
        ExternalFeeCustomEntry(navigator = navigator)
    }

    entry<Routes.ExternalSuccess> {
        ExternalSuccessScreen(
            onContinue = { navigator.navigateToHome() },
        )
    }

    entry<Routes.LnurlChannel> { route ->
        LnurlChannelScreen(
            uri = route.uri,
            callback = route.callback,
            k1 = route.k1,
            onConnected = { navigator.navigate(Routes.ExternalSuccess) },
            onBack = { navigator.goBack() },
            onClose = { navigator.navigateToHome() },
        )
    }

    entry<Routes.ExternalNodeScanner> {
        QrScanningScreen(
            navigator = navigator,
            onScanSuccess = { qrCode ->
                navigator.navigate(Routes.ExternalConnection(scannedNodeUri = qrCode))
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
        onNodeConnected = { navigator.navigate(Routes.ExternalAmount) },
        onScanClick = { navigator.navigate(Routes.ExternalNodeScanner) },
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
        onContinue = { navigator.navigate(Routes.ExternalConfirm) },
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
            navigator.navigate(Routes.ExternalSuccess)
        },
        onNetworkFeeClick = { navigator.navigate(Routes.ExternalFeeCustom) },
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
