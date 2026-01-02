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
            walletViewModel = walletViewModel,
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

    entry<Routes.Activity.All> {
        AllActivityScreen(
            viewModel = activityListViewModel,
            onBack = {
                activityListViewModel.clearFilters()
                navigator.navigateToHome()
            },
            onActivityItemClick = { navigator.navigate(Routes.Activity.Detail(it)) },
            onTagClick = { navigator.navigate(Routes.Activity.TagSelectorSheet) },
            onDateRangeClick = { navigator.navigate(Routes.Activity.DateRangeSelectorSheet) },
            onEmptyActivityRowClick = {
                walletViewModel.resetReceiveState()
                navigator.navigate(Routes.Receive.Qr)
            },
        )
    }

    entry<Routes.Activity.Detail> { route ->
        ActivityDetailScreen(
            navigator = navigator,
            activityId = route.activity.rawId(),
            listViewModel = activityListViewModel,
        )
    }

    entry<Routes.Activity.Explore> { route ->
        ActivityExploreScreen(
            navigator = navigator,
            activity = route.activity,
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

    profileEntries(navigator, settingsViewModel)

    shopEntries(navigator, appViewModel, settingsViewModel)

    entry<Routes.BuyIntro> {
        BuyIntroScreen(
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.CriticalUpdate> {
        CriticalUpdateScreen()
    }

    recoveryEntries(navigator, appViewModel, settingsViewModel)
}

@Composable
private fun SavingsEntry(
    navigator: Navigator,
    walletViewModel: WalletViewModel,
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
        onAllActivityButtonClick = { navigator.navigate(Routes.Activity.All) },
        onActivityItemClick = { navigator.navigate(Routes.Activity.Detail(it)) },
        onEmptyActivityRowClick = {
            walletViewModel.resetReceiveState()
            navigator.navigate(Routes.Receive.Qr)
        },
        onTransferToSpendingClick = {
            if (!hasSeenSpendingIntro) {
                navigator.navigate(Routes.Transfer.ToSpending.Intro)
            } else {
                navigator.navigate(Routes.Transfer.ToSpending.Amount)
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
        onAllActivityButtonClick = { navigator.navigate(Routes.Activity.All) },
        onActivityItemClick = { navigator.navigate(Routes.Activity.Detail(it)) },
        onEmptyActivityRowClick = {
            walletViewModel.resetReceiveState()
            navigator.navigate(Routes.Receive.Qr)
        },
        onTransferToSavingsClick = {
            if (!hasSeenSavingsIntro) {
                navigator.navigate(Routes.Transfer.ToSavings.Intro)
            } else {
                navigator.navigate(Routes.Transfer.ToSavings.Availability)
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

private fun EntryProviderScope<NavKey>.profileEntries(
    navigator: Navigator,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.Profile.Intro> {
        ProfileIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenProfileIntro(true)
                navigator.navigate(Routes.Profile.Create)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Profile.Create> {
        CreateProfileScreen(
            onBack = { navigator.goBack() },
        )
    }
}

private fun EntryProviderScope<NavKey>.shopEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.Shop.Intro> {
        ShopIntroScreen(
            onContinue = {
                settingsViewModel.setHasSeenShopIntro(true)
                navigator.navigate(Routes.Shop.Discover)
            },
            onBackClick = { navigator.goBack() },
        )
    }

    entry<Routes.Shop.Discover> {
        ShopDiscoverScreen(
            onBack = { navigator.goBack() },
            navigateWebView = { page, title ->
                navigator.navigate(Routes.Shop.WebView(page, title))
            },
        )
    }

    entry<Routes.Shop.WebView> { route ->
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

private fun EntryProviderScope<NavKey>.recoveryEntries(
    navigator: Navigator,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
) {
    entry<Routes.Recovery.Mode> {
        RecoveryModeScreen(
            appViewModel = appViewModel,
            settingsViewModel = settingsViewModel,
            onNavigateToSeed = { navigator.navigate(Routes.Recovery.Mnemonic) },
        )
    }

    entry<Routes.Recovery.Mnemonic> {
        RecoveryMnemonicScreen(
            onNavigateBack = { navigator.goBack() },
        )
    }
}
