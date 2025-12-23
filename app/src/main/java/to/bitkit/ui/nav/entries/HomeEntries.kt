package to.bitkit.ui.nav.entries

import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import to.bitkit.ext.rawId
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.nav.Transitions
import to.bitkit.ui.screens.CriticalUpdateScreen
import to.bitkit.ui.screens.profile.CreateProfileScreen
import to.bitkit.ui.screens.profile.ProfileIntroScreen
import to.bitkit.ui.screens.recovery.RecoveryMnemonicScreen
import to.bitkit.ui.screens.recovery.RecoveryModeScreen
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.shop.ShopIntroScreen
import to.bitkit.ui.screens.shop.shopDiscover.ShopDiscoverScreen
import to.bitkit.ui.screens.shop.shopWebView.ShopWebViewScreen
import to.bitkit.ui.screens.wallets.HomeScreen
import to.bitkit.ui.screens.wallets.SavingsWalletScreen
import to.bitkit.ui.screens.wallets.SpendingWalletScreen
import to.bitkit.ui.screens.wallets.activity.ActivityDetailScreen
import to.bitkit.ui.screens.wallets.activity.ActivityExploreScreen
import to.bitkit.ui.screens.wallets.activity.AllActivityScreen
import to.bitkit.ui.screens.wallets.suggestion.BuyIntroScreen
import to.bitkit.ui.utils.RequestNotificationPermissions
import to.bitkit.viewmodels.ActivityListViewModel
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.WalletViewModel

/**
 * Home section entry providers for Navigation 3.
 */
@Suppress("LongParameterList", "LongMethod")
fun EntryProviderScope<NavKey>.homeEntries(
    navigator: Navigator,
    drawerState: DrawerState,
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.Home> {
        HomeEntry(
            navigator = navigator,
            drawerState = drawerState,
            walletViewModel = walletViewModel,
            appViewModel = appViewModel,
            activityListViewModel = activityListViewModel,
            settingsViewModel = settingsViewModel,
        )
    }

    entry<Routes.Savings> {
        SavingsEntry(
            navigator = navigator,
            appViewModel = appViewModel,
            activityListViewModel = activityListViewModel,
            settingsViewModel = settingsViewModel,
        )
    }

    entry<Routes.Spending> {
        SpendingEntry(
            navigator = navigator,
            walletViewModel = walletViewModel,
            activityListViewModel = activityListViewModel,
            settingsViewModel = settingsViewModel,
        )
    }

    entry<Routes.AllActivity> {
        AllActivityScreen(
            viewModel = activityListViewModel,
            onBack = {
                activityListViewModel.clearFilters()
                navigator.navigateToHome()
            },
            onActivityItemClick = { navigator.navigate(Routes.ActivityDetail(it)) },
            onTagClick = { navigator.navigate(Routes.ActivityTagSelectorSheet) },
            onDateRangeClick = { navigator.navigate(Routes.ActivityDateRangeSelectorSheet) },
            onEmptyActivityRowClick = { navigator.navigate(Routes.ReceiveQr) },
        )
    }

    entry<Routes.ActivityDetail> { route ->
        ActivityDetailScreen(
            navigator = navigator,
            activityId = route.activity.rawId(),
            listViewModel = activityListViewModel,
        )
    }

    entry<Routes.ActivityExplore> { route ->
        ActivityExploreScreen(
            navigator = navigator,
            activityId = route.id,
        )
    }

    entry<Routes.QrScanner>(
        metadata = Transitions.verticalSlideMetadata
    ) {
        QrScanningScreen(
            navigator = navigator,
            onScanSuccess = { qrCode ->
                appViewModel.onScanResult(qrCode)
            },
        )
    }

    // Profile Flow
    profileEntries(navigator, settingsViewModel)

    // Shop Flow
    shopEntries(navigator, appViewModel, settingsViewModel)

    // Buy Flow
    entry<Routes.BuyIntro> {
        BuyIntroScreen(
            onBackClick = { navigator.goBack() },
        )
    }

    // App Status
    entry<Routes.CriticalUpdate> {
        CriticalUpdateScreen()
    }

    // Recovery Flow
    recoveryEntries(navigator, appViewModel, settingsViewModel)
}

@Composable
private fun SavingsEntry(
    navigator: Navigator,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val hasSeenSpendingIntro by settingsViewModel.hasSeenSpendingIntro.collectAsStateWithLifecycle()
    val isGeoBlocked by appViewModel.isGeoBlocked.collectAsStateWithLifecycle()
    val onchainActivities by activityListViewModel.onchainActivities.collectAsStateWithLifecycle()

    SavingsWalletScreen(
        isGeoBlocked = isGeoBlocked,
        onchainActivities = onchainActivities.orEmpty(),
        onAllActivityButtonClick = { navigator.navigate(Routes.AllActivity) },
        onActivityItemClick = { navigator.navigate(Routes.ActivityDetail(it)) },
        onEmptyActivityRowClick = { navigator.navigate(Routes.ReceiveQr) },
        onTransferToSpendingClick = {
            if (!hasSeenSpendingIntro) {
                navigator.navigate(Routes.SpendingIntro)
            } else {
                navigator.navigate(Routes.SpendingAmount)
            }
        },
        onBackClick = { navigator.goBack() },
    )
}

@Composable
private fun SpendingEntry(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val hasSeenSavingsIntro by settingsViewModel.hasSeenSavingsIntro.collectAsStateWithLifecycle()
    val uiState by walletViewModel.uiState.collectAsStateWithLifecycle()
    val lightningActivities by activityListViewModel.lightningActivities.collectAsStateWithLifecycle()

    SpendingWalletScreen(
        uiState = uiState,
        lightningActivities = lightningActivities.orEmpty(),
        onAllActivityButtonClick = { navigator.navigate(Routes.AllActivity) },
        onActivityItemClick = { navigator.navigate(Routes.ActivityDetail(it)) },
        onEmptyActivityRowClick = { navigator.navigate(Routes.ReceiveQr) },
        onTransferToSavingsClick = {
            if (!hasSeenSavingsIntro) {
                navigator.navigate(Routes.SavingsIntro)
            } else {
                navigator.navigate(Routes.SavingsAvailability)
            }
        },
        onBackClick = { navigator.goBack() },
    )
}

@Composable
private fun HomeEntry(
    navigator: Navigator,
    drawerState: DrawerState,
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    activityListViewModel: ActivityListViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val mainUiState by walletViewModel.uiState.collectAsStateWithLifecycle()
    val isRecoveryMode by walletViewModel.isRecoveryMode.collectAsStateWithLifecycle()

    RequestNotificationPermissions(
        showPermissionDialog = !isRecoveryMode,
        onPermissionChange = { granted ->
            settingsViewModel.setNotificationPreference(granted)
        }
    )

    HomeScreen(
        mainUiState = mainUiState,
        drawerState = drawerState,
        navigator = navigator,
        settingsViewModel = settingsViewModel,
        walletViewModel = walletViewModel,
        appViewModel = appViewModel,
        activityListViewModel = activityListViewModel,
    )
}

/**
 * Profile flow entries.
 */
private fun EntryProviderScope<NavKey>.profileEntries(
    navigator: Navigator,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.ProfileIntro> {
        ProfileIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenProfileIntro(true)
                navigator.navigate(Routes.CreateProfile)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.CreateProfile> {
        CreateProfileScreen(
            onBack = { navigator.goBack() },
        )
    }
}

/**
 * Shop flow entries.
 */
private fun EntryProviderScope<NavKey>.shopEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.ShopIntro> {
        ShopIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenShopIntro(true)
                navigator.navigate(Routes.ShopDiscover)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.ShopDiscover> {
        ShopDiscoverScreen(
            onBack = { navigator.goBack() },
            navigateWebView = { page, title ->
                navigator.navigate(Routes.ShopWebView(page, title))
            },
        )
    }

    entry<Routes.ShopWebView> { route ->
        ShopWebViewScreen(
            page = route.page,
            title = route.title,
            onClose = { navigator.navigateToHome() },
            onBack = { navigator.goBack() },
            onPaymentIntent = { data ->
                appViewModel.onScanResult(data)
            },
        )
    }
}

/**
 * Recovery flow entries.
 */
private fun EntryProviderScope<NavKey>.recoveryEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.RecoveryMode> {
        RecoveryModeScreen(
            appViewModel = appViewModel,
            settingsViewModel = settingsViewModel,
            onNavigateToSeed = { navigator.navigate(Routes.RecoveryMnemonic) },
        )
    }

    entry<Routes.RecoveryMnemonic> {
        RecoveryMnemonicScreen(
            onNavigateBack = { navigator.goBack() },
        )
    }
}
