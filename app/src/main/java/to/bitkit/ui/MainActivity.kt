package to.bitkit.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.lightningdevkit.ldknode.Event
import to.bitkit.env.Tag.LDK
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.Toast
import to.bitkit.ui.components.ToastOverlay
import to.bitkit.ui.onboarding.CreateWalletWithPassphraseScreen
import to.bitkit.ui.onboarding.IntroScreen
import to.bitkit.ui.onboarding.OnboardingSlidesScreen
import to.bitkit.ui.onboarding.RestoreWalletView
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.screens.SplashScreen
import to.bitkit.ui.screens.wallets.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.clearBackStack
import to.bitkit.ui.utils.enableAppEdgeToEdge
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.WalletViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appViewModel by viewModels<AppViewModel>()
    private val walletViewModel by viewModels<WalletViewModel>()
    private val blocktankViewModel by viewModels<BlocktankViewModel>()
    private val currencyViewModel by viewModels<CurrencyViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        installSplashScreen()
        enableAppEdgeToEdge()
        walletViewModel.setOnEvent(::onLdkEvent)
        setContent {
            AppThemeSurface {
                val scope = rememberCoroutineScope()
                if (!walletViewModel.walletExists) {
                    val startupNavController = rememberNavController()
                    NavHost(
                        navController = startupNavController,
                        startDestination = StartupRoutes.Terms,
                    ) {
                        composable<StartupRoutes.Terms> {
                            TermsOfUseScreen(
                                onNavigateToIntro = {
                                    startupNavController.navigate(StartupRoutes.Intro) { clearBackStack() }
                                }
                            )
                        }
                        composable<StartupRoutes.Intro> {
                            IntroScreen(
                                onStartClick = {
                                    startupNavController.navigate(StartupRoutes.Slides()) { clearBackStack() }
                                },
                                onSkipClick = {
                                    startupNavController.navigate(StartupRoutes.Slides(4)) { clearBackStack() }
                                },
                            )
                        }
                        composable<StartupRoutes.Slides> { navBackEntry ->
                            val route = navBackEntry.toRoute<StartupRoutes.Slides>()
                            OnboardingSlidesScreen(
                                currentTab = route.tab,
                                onAdvancedSetupClick = { startupNavController.navigate(StartupRoutes.Advanced) },
                                onCreateClick = {
                                    scope.launch {
                                        try {
                                            walletViewModel.setInitNodeLifecycleState(isInitializingWallet = true)
                                            walletViewModel.createWallet(bip39Passphrase = null)
                                            walletViewModel.setWalletExistsState()
                                        } catch (e: Exception) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                },
                                onRestoreClick = { startupNavController.navigate(StartupRoutes.Restore) },
                            )
                        }
                        composable<StartupRoutes.Restore> {
                            RestoreWalletView(
                                onBackClick = { startupNavController.popBackStack() },
                                onRestoreClick = { mnemonic, passphrase ->
                                    scope.launch {
                                        try {
                                            walletViewModel.setInitNodeLifecycleState(isInitializingWallet = true)
                                            walletViewModel.restoreWallet(mnemonic, passphrase)
                                            walletViewModel.setWalletExistsState()
                                        } catch (e: Exception) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                }
                            )
                        }
                        composable<StartupRoutes.Advanced> {
                            CreateWalletWithPassphraseScreen(
                                onBackClick = { startupNavController.popBackStack() },
                                onCreateClick = { passphrase ->
                                    scope.launch {
                                        try {
                                            walletViewModel.setInitNodeLifecycleState(isInitializingWallet = true)
                                            walletViewModel.createWallet(bip39Passphrase = passphrase)
                                            walletViewModel.setWalletExistsState()
                                        } catch (e: Exception) {
                                            appViewModel.toast(e)
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    ContentView(
                        appViewModel = appViewModel,
                        walletViewModel = walletViewModel,
                        blocktankViewModel = blocktankViewModel,
                        currencyViewModel = currencyViewModel,
                    )
                }

                ToastOverlay(
                    toast = appViewModel.currentToast,
                    onDismiss = {
                        appViewModel.hideToast()
                    }
                )

                if (appViewModel.showNewTransaction) {
                    NewTransactionSheet(appViewModel)
                }

                SplashScreen(appViewModel.splashVisible)
            }
        }
    }

    private fun onLdkEvent(event: Event) = runOnUiThread {
        try {
            when (event) {
                is Event.PaymentReceived -> {
                    appViewModel.showNewTransactionSheet(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.RECEIVED,
                            sats = (event.amountMsat / 1000u).toLong(),
                        )
                    )
                }

                is Event.ChannelPending -> {
                    // Only relevant for channels to external nodes
                }

                is Event.ChannelReady -> {
                    // TODO: handle cjit as payment received
                    val channel = walletViewModel.findChannelById(event.channelId)
                    if (channel != null) {
                        appViewModel.showNewTransactionSheet(
                            NewTransactionSheetDetails(
                                type = NewTransactionSheetType.LIGHTNING,
                                direction = NewTransactionSheetDirection.RECEIVED,
                                sats = (channel.inboundCapacityMsat / 1000u).toLong(),
                            )
                        )
                    } else {
                        appViewModel.toast(
                            type = Toast.ToastType.ERROR,
                            title = "Channel opened",
                            description = "Ready to send"
                        )
                    }
                }

                is Event.ChannelClosed -> {
                    appViewModel.toast(
                        type = Toast.ToastType.LIGHTNING,
                        title = "Channel closed",
                        description = "Balance moved from spending to savings"
                    )
                }

                is Event.PaymentSuccessful -> {
                    appViewModel.showNewTransactionSheet(
                        NewTransactionSheetDetails(
                            type = NewTransactionSheetType.LIGHTNING,
                            direction = NewTransactionSheetDirection.SENT,
                            sats = ((event.feePaidMsat ?: 0u) / 1000u).toLong(),
                        )
                    )
                }

                is Event.PaymentClaimable -> Unit
                is Event.PaymentFailed -> {
                    appViewModel.toast(
                        type = Toast.ToastType.ERROR,
                        title = "Payment failed",
                        description = event.reason?.name ?: "Unknown error"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(LDK, "Ldk event handler error", e)
        }
    }
}

private object StartupRoutes {
    @Serializable
    data object Terms

    @Serializable
    data object Intro

    @Serializable
    data class Slides(val tab: Int = 0)

    @Serializable
    data object Restore

    @Serializable
    data object Advanced
}
