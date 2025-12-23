package to.bitkit.ui.nav.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.onboarding.CreateWalletWithPassphraseScreen
import to.bitkit.ui.onboarding.IntroScreen
import to.bitkit.ui.onboarding.OnboardingSlidesScreen
import to.bitkit.ui.onboarding.RestoreWalletScreen
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.onboarding.WarningMultipleDevicesScreen

private const val LAST_SLIDE_INDEX = 4

fun EntryProviderScope<NavKey>.onboardingEntries(
    navigator: Navigator,
    isGeoBlocked: Boolean,
    onCreateWallet: (passphrase: String?) -> Unit,
    onRestoreWallet: (mnemonic: String, passphrase: String?) -> Unit,
) {
    entry<Routes.Terms> {
        TermsOfUseScreen(
            onNavigateToIntro = { navigator.navigate(Routes.Intro) }
        )
    }

    entry<Routes.Intro> {
        IntroScreen(
            onStartClick = { navigator.navigate(Routes.Slides()) },
            onSkipClick = { navigator.navigate(Routes.Slides(LAST_SLIDE_INDEX)) },
        )
    }

    entry<Routes.Slides> { route ->
        OnboardingSlidesScreen(
            currentTab = route.tab,
            isGeoBlocked = isGeoBlocked,
            onAdvancedSetupClick = { navigator.navigate(Routes.Advanced) },
            onCreateClick = { onCreateWallet(null) },
            onRestoreClick = { navigator.navigate(Routes.WarningMultipleDevices) },
        )
    }

    entry<Routes.WarningMultipleDevices> {
        WarningMultipleDevicesScreen(
            onBackClick = { navigator.goBack() },
            onConfirmClick = { navigator.navigate(Routes.Restore) }
        )
    }

    entry<Routes.Restore> {
        RestoreWalletScreen(
            onBackClick = { navigator.goBack() },
            onRestoreClick = onRestoreWallet
        )
    }

    entry<Routes.Advanced> {
        CreateWalletWithPassphraseScreen(
            onBackClick = { navigator.goBack() },
            onCreateClick = onCreateWallet,
        )
    }
}
