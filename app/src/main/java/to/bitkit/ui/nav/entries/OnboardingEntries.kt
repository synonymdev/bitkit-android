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

private const val INDEX_LAST_SLIDE = 4

fun EntryProviderScope<NavKey>.onboardingEntries(
    navigator: Navigator,
    isGeoBlocked: Boolean,
    onCreateWallet: (passphrase: String?) -> Unit,
    onRestoreWallet: (mnemonic: String, passphrase: String?) -> Unit,
) {
    entry<Routes.Onboarding.Terms> {
        TermsOfUseScreen(
            onNavigateToIntro = { navigator.navigate(Routes.Onboarding.Intro) }
        )
    }

    entry<Routes.Onboarding.Intro> {
        IntroScreen(
            onStartClick = { navigator.navigate(Routes.Onboarding.Slides()) },
            onSkipClick = { navigator.navigate(Routes.Onboarding.Slides(INDEX_LAST_SLIDE)) },
        )
    }

    entry<Routes.Onboarding.Slides> { route ->
        OnboardingSlidesScreen(
            currentTab = route.tab,
            isGeoBlocked = isGeoBlocked,
            onAdvancedSetupClick = { navigator.navigate(Routes.Onboarding.Advanced) },
            onCreateClick = { onCreateWallet(null) },
            onRestoreClick = { navigator.navigate(Routes.Onboarding.WarningMultipleDevices) },
        )
    }

    entry<Routes.Onboarding.WarningMultipleDevices> {
        WarningMultipleDevicesScreen(
            onBackClick = { navigator.goBack() },
            onConfirmClick = { navigator.navigate(Routes.Onboarding.Restore) }
        )
    }

    entry<Routes.Onboarding.Restore> {
        RestoreWalletScreen(
            onBackClick = { navigator.goBack() },
            onRestoreClick = onRestoreWallet
        )
    }

    entry<Routes.Onboarding.Advanced> {
        CreateWalletWithPassphraseScreen(
            onBackClick = { navigator.goBack() },
            onCreateClick = onCreateWallet,
        )
    }
}
