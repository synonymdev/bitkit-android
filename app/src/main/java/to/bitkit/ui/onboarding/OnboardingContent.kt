package to.bitkit.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Transitions
import to.bitkit.ui.nav.entries.onboardingEntries
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun OnboardingContent(
    navigator: Navigator,
    scope: CoroutineScope,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
) {
    val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()

    NavDisplay(
        backStack = navigator.backStack,
        onBack = { navigator.goBack() },
        transitionSpec = Transitions.screenDefault,
        popTransitionSpec = Transitions.screenDefaultPop,
        predictivePopTransitionSpec = Transitions.screenDefaultPredictivePop,
        entryProvider = entryProvider {
            onboardingEntries(
                navigator = navigator,
                isGeoBlocked = isGeoBlocked,
                onCreateWallet = { passphrase ->
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.setInitNodeLifecycleState()
                            walletViewModel.createWallet(bip39Passphrase = passphrase)
                        }.onFailure { appViewModel.toast(it) }
                    }
                },
                onRestoreWallet = { mnemonic, passphrase ->
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.restoreWallet(mnemonic, passphrase)
                        }.onFailure { appViewModel.toast(it) }
                    }
                },
            )
        }
    )
}
