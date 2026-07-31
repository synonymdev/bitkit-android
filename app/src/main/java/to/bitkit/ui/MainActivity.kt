package to.bitkit.ui

import android.app.NotificationManager
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.androidServices.LightningNodeService
import to.bitkit.androidServices.LightningNodeService.Companion.ACTION_START_SERVICE
import to.bitkit.androidServices.LightningNodeService.Companion.CHANNEL_ID_NODE
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.ui.components.AuthCheckView
import to.bitkit.ui.components.IsOnlineTracker
import to.bitkit.ui.components.ToastOverlay
import to.bitkit.ui.onboarding.CreateWalletWithPassphraseScreen
import to.bitkit.ui.onboarding.IntroScreen
import to.bitkit.ui.onboarding.OnboardingSlidesScreen
import to.bitkit.ui.onboarding.RestoreWalletScreen
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.onboarding.WarningMultipleDevicesScreen
import to.bitkit.ui.screens.MigrationLoadingScreen
import to.bitkit.ui.screens.SplashScreen
import to.bitkit.ui.sheets.ForgotPinSheet
import to.bitkit.ui.sheets.NewTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.utils.ScreenDeepLinks
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.enableAppEdgeToEdge
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.BackupsViewModel
import to.bitkit.viewmodels.BlocktankViewModel
import to.bitkit.viewmodels.CurrencyViewModel
import to.bitkit.viewmodels.MainScreenEffect
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.TransferViewModel
import to.bitkit.viewmodels.WalletViewModel

private const val TREZOR_WEBUSB_VENDOR_ID = 0x1209
private const val TREZOR_WEBUSB_FIRMWARE_PRODUCT_ID = 0x53C1
private const val TREZOR_WEBUSB_BOOTLOADER_PRODUCT_ID = 0x53C0
private const val TREZOR_LEGACY_VENDOR_ID = 0x534C
private const val TREZOR_LEGACY_PRODUCT_ID = 0x0001

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private companion object {
        const val KEY_CONSUMED_LAUNCH_INTENT = "consumed_launch_intent"
    }

    private val appViewModel by viewModels<AppViewModel>()
    private val walletViewModel by viewModels<WalletViewModel>()
    private val blocktankViewModel by viewModels<BlocktankViewModel>()
    private val currencyViewModel by viewModels<CurrencyViewModel>()
    private val activityListViewModel by viewModels<ActivityListViewModel>()
    private val transferViewModel by viewModels<TransferViewModel>()
    private val settingsViewModel by viewModels<SettingsViewModel>()
    private val backupsViewModel by viewModels<BackupsViewModel>()

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNotificationChannel()
        initNotificationChannel(
            id = CHANNEL_ID_NODE,
            name = getString(R.string.notification__channel_node__name),
            desc = getString(R.string.notification__channel_node__body),
            importance = NotificationManager.IMPORTANCE_LOW
        )

        val consumedLaunchIntent = savedInstanceState?.getString(KEY_CONSUMED_LAUNCH_INTENT)
        val currentLaunchIntent = intent.launchKey()
        if (currentLaunchIntent == null || currentLaunchIntent != consumedLaunchIntent) {
            handleLaunchIntent(intent)
        }

        installSplashScreen()
        enableAppEdgeToEdge()
        setContent {
            AppThemeSurface(
                modifier = Modifier.semantics {
                    testTagsAsResourceId = true // see https://github.com/appium/appium/issues/15138
                }
            ) {
                val scope = rememberCoroutineScope()
                val isRecoveryMode by walletViewModel.isRecoveryMode.collectAsStateWithLifecycle()
                val notificationsGranted by settingsViewModel.notificationsGranted.collectAsStateWithLifecycle()
                val keepActive by settingsViewModel.keepBitkitActiveInBackground.collectAsStateWithLifecycle()
                val walletExists = walletViewModel.walletExists
                val isShowingMigrationLoading by walletViewModel.isShowingMigrationLoading.collectAsStateWithLifecycle()
                val restoreState by walletViewModel.restoreState.collectAsStateWithLifecycle()
                val hazeState = rememberHazeState(blurEnabled = true)

                LaunchedEffect(
                    walletExists,
                    isRecoveryMode,
                    notificationsGranted,
                    keepActive,
                    restoreState,
                ) {
                    val canStartService = walletExists && notificationsGranted && keepActive && restoreState.isIdle()
                    if (canStartService && !isRecoveryMode) {
                        tryStartForegroundService()
                    } else {
                        stopForegroundService()
                    }
                }

                if (isShowingMigrationLoading && !isRecoveryMode) {
                    MigrationLoadingScreen(isVisible = true)
                } else if (!walletViewModel.walletExists && !isRecoveryMode) {
                    OnboardingNav(
                        startupNavController = rememberNavController(),
                        scope = scope,
                        appViewModel = appViewModel,
                        walletViewModel = walletViewModel,
                    )
                } else {
                    val isAuthenticated by appViewModel.isAuthenticated.collectAsStateWithLifecycle()

                    IsOnlineTracker(appViewModel)
                    ContentView(
                        appViewModel = appViewModel,
                        walletViewModel = walletViewModel,
                        blocktankViewModel = blocktankViewModel,
                        currencyViewModel = currencyViewModel,
                        activityListViewModel = activityListViewModel,
                        transferViewModel = transferViewModel,
                        settingsViewModel = settingsViewModel,
                        backupsViewModel = backupsViewModel,
                        hazeState = hazeState,
                        modifier = Modifier.hazeSource(hazeState, zIndex = 0f),
                    )

                    AnimatedVisibility(
                        visible = !isAuthenticated,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        AuthCheckView(
                            showLogoOnPin = true,
                            appViewModel = appViewModel,
                            settingsViewModel = settingsViewModel,
                            onSuccess = { appViewModel.setIsAuthenticated(true) },
                        )
                    }

                    val showForgotPinSheet by appViewModel.showForgotPinSheet.collectAsStateWithLifecycle()
                    if (showForgotPinSheet) {
                        ForgotPinSheet(
                            onDismiss = { appViewModel.setShowForgotPin(false) },
                            onResetClick = { walletViewModel.wipeWallet() },
                        )
                    }

                    LaunchedEffect(appViewModel) {
                        appViewModel.mainScreenEffect.collect {
                            when (it) {
                                MainScreenEffect.WipeWallet -> walletViewModel.wipeWallet()
                                else -> Unit
                            }
                        }
                    }
                }

                val currentToast by appViewModel.currentToast.collectAsStateWithLifecycle()
                ToastOverlay(
                    toast = currentToast,
                    hazeState = hazeState,
                    onDismiss = { appViewModel.hideToast() },
                    onDragStart = { appViewModel.pauseToast() },
                    onDragEnd = { appViewModel.resumeToast() }
                )

                val transactionSheetDetails by appViewModel.transactionSheet.collectAsStateWithLifecycle()
                if (transactionSheetDetails != NewTransactionSheetDetails.EMPTY) {
                    NewTransactionSheet(
                        appViewModel = appViewModel,
                        currencyViewModel = currencyViewModel,
                        settingsViewModel = settingsViewModel,
                    )
                }

                SplashScreen(appViewModel.splashVisible)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (!isMainThread()) {
            runOnUiThread { onNewIntent(intent) }
            return
        }

        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbAttachIntent(intent)
            return
        }

        val isScreenLink = intent.data?.let { ScreenDeepLinks.isScreenDeepLink(it) } == true

        appViewModel.handleDeeplinkIntent(intent)

        if (isScreenLink) {
            intent.data = null
            setIntent(intent)
        }
    }

    /**
     * The OS delivers the USB attach event as an activity intent (via the app picker),
     * not as a broadcast, so it is forwarded from here to trigger the silent reconnect.
     */
    private fun handleUsbAttachIntent(intent: Intent) {
        val device = intent.usbDevice()
        if (device == null) {
            appViewModel.onUsbDeviceAttached()
            return
        }
        if (!device.isSupportedTrezorDevice()) return

        appViewModel.onUsbDeviceAttached(
            deviceId = device.deviceName.takeUnless { device.isTrezorBootloader() },
            deviceModel = getString(R.string.hardware__device_model_trezor),
        )
    }

    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        intent.launchKey()?.let { outState.putString(KEY_CONSUMED_LAUNCH_INTENT, it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!settingsViewModel.notificationsGranted.value) {
            stopForegroundService()
        }
    }

    /**
     * Attempts to start the LightningNodeService if it's not already running.
     */
    private fun tryStartForegroundService() {
        runCatching {
            Logger.debug("Attempting to start LightningNodeService", context = "MainActivity")
            startForegroundService(
                Intent(this, LightningNodeService::class.java).apply {
                    action = ACTION_START_SERVICE
                },
            )
        }.onFailure { error ->
            Logger.error("Failed to start LightningNodeService", error, context = "MainActivity")
        }
    }

    private fun stopForegroundService() {
        runCatching {
            stopService(Intent(this, LightningNodeService::class.java))
        }.onFailure { error ->
            Logger.error("Failed to stop LightningNodeService", error, context = "MainActivity")
        }
    }
}

internal fun Intent?.launchKey(): String? {
    this ?: return null
    return when (action) {
        in AppViewModel.DEEPLINK_ACTIONS -> data?.toString()?.let {
            SamRockSetupRequest.sanitizedLaunchKey(it) ?: it
        }
        UsbManager.ACTION_USB_DEVICE_ATTACHED -> listOfNotNull(action, usbDevice()?.deviceName).joinToString(":")
        else -> null
    }
}

private fun Intent.usbDevice(): UsbDevice? =
    IntentCompat.getParcelableExtra(this, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

private fun UsbDevice.isSupportedTrezorDevice() = isTrezorFirmwareDevice() || isTrezorBootloader()

private fun UsbDevice.isTrezorFirmwareDevice() =
    (vendorId == TREZOR_WEBUSB_VENDOR_ID && productId == TREZOR_WEBUSB_FIRMWARE_PRODUCT_ID) ||
        (vendorId == TREZOR_LEGACY_VENDOR_ID && productId == TREZOR_LEGACY_PRODUCT_ID)

private fun UsbDevice.isTrezorBootloader() =
    vendorId == TREZOR_WEBUSB_VENDOR_ID && productId == TREZOR_WEBUSB_BOOTLOADER_PRODUCT_ID

@Composable
private fun OnboardingNav(
    startupNavController: NavHostController,
    scope: CoroutineScope,
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
) {
    NavHost(
        navController = startupNavController,
        startDestination = StartupRoutes.Terms,
    ) {
        composable<StartupRoutes.Terms> {
            TermsOfUseScreen(
                onNavigateToIntro = {
                    startupNavController.navigateTo(StartupRoutes.Intro)
                }
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Intro> {
            IntroScreen(
                onStartClick = {
                    startupNavController.navigateTo(StartupRoutes.Slides())
                },
                onSkipClick = {
                    startupNavController.navigateTo(StartupRoutes.Slides(StartupRoutes.LAST_SLIDE_INDEX))
                },
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Slides> { navBackEntry ->
            val route = navBackEntry.toRoute<StartupRoutes.Slides>()
            val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()
            OnboardingSlidesScreen(
                currentTab = route.tab,
                isGeoBlocked = isGeoBlocked,
                onAdvancedSetupClick = { startupNavController.navigateTo(StartupRoutes.Advanced) },
                onCreateClick = {
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.setInitNodeLifecycleState()
                            walletViewModel.createWallet(bip39Passphrase = null)
                        }.onFailure {
                            appViewModel.toast(it)
                        }
                    }
                },
                onRestoreClick = {
                    startupNavController.navigateTo(
                        StartupRoutes.WarningMultipleDevices
                    )
                },
            )
        }
        composableWithDefaultTransitions<StartupRoutes.WarningMultipleDevices> {
            WarningMultipleDevicesScreen(
                onBackClick = {
                    startupNavController.popBackStack()
                },
                onConfirmClick = {
                    startupNavController.navigateTo(StartupRoutes.Restore)
                }
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Restore> {
            RestoreWalletScreen(
                onBackClick = { startupNavController.popBackStack() },
                onRestoreClick = { mnemonic, passphrase ->
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.restoreWallet(mnemonic, passphrase)
                        }.onFailure {
                            appViewModel.toast(it)
                        }
                    }
                }
            )
        }
        composableWithDefaultTransitions<StartupRoutes.Advanced> {
            CreateWalletWithPassphraseScreen(
                onBackClick = { startupNavController.popBackStack() },
                onCreateClick = { passphrase ->
                    scope.launch {
                        runCatching {
                            appViewModel.resetIsAuthenticatedState()
                            walletViewModel.createWallet(bip39Passphrase = passphrase)
                        }.onFailure {
                            appViewModel.toast(it)
                        }
                    }
                },
            )
        }
    }
}

private object StartupRoutes {
    const val LAST_SLIDE_INDEX = 4

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

    @Serializable
    data object WarningMultipleDevices
}
