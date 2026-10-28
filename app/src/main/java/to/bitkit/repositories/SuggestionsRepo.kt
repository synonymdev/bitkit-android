package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.models.Suggestion
import to.bitkit.models.toSuggestionOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuggestionsRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
    private val pubkyRepo: PubkyRepo,
    private val hwWalletRepo: HwWalletRepo,
) {
    companion object {
        private const val MAX_SUGGESTIONS = 4
    }

    val suggestionsFlow: Flow<List<Suggestion>> = combine(
        walletRepo.balanceState,
        settingsStore.data,
        pubkyRepo.isAuthenticated,
        hwWalletRepo.wallets,
    ) { balanceState, settings, profileAuthenticated, hardwareWallets ->
        val hasHardwareWallet = hardwareWallets.isNotEmpty()
        val baseSuggestions = if (balanceState.totalSats > 0uL) {
            fundedWalletSuggestions(settings, profileAuthenticated, hasHardwareWallet)
        } else {
            emptyWalletSuggestions(settings, profileAuthenticated, hasHardwareWallet)
        }
        val dismissedList = settings.dismissedSuggestions.mapNotNull { it.toSuggestionOrNull() }
        baseSuggestions
            .filterNot { it in dismissedList }
            .take(MAX_SUGGESTIONS)
    }

    suspend fun resetDismissedSuggestionsIfEmpty() = withContext(bgDispatcher) {
        if (suggestionsFlow.first().isEmpty()) {
            settingsStore.update { it.copy(dismissedSuggestions = emptyList()) }
        }
    }

    private fun fundedWalletSuggestions(
        settings: SettingsData,
        profileAuthenticated: Boolean,
        hasHardwareWallet: Boolean,
    ) = listOfNotNull(
        Suggestion.BACK_UP.takeIf { !settings.backupVerified },
        Suggestion.SECURE.takeIf { !settings.isPinEnabled },
        Suggestion.QUICK_PAY.takeIf { !settings.isQuickPayEnabled },
        Suggestion.HARDWARE.takeIf { !hasHardwareWallet },
        Suggestion.SHOP,
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.SUPPORT,
        Suggestion.INVITE,
        Suggestion.BUY,
    )

    private fun emptyWalletSuggestions(
        settings: SettingsData,
        profileAuthenticated: Boolean,
        hasHardwareWallet: Boolean,
    ) = listOfNotNull(
        Suggestion.BUY,
        Suggestion.HARDWARE.takeIf { !hasHardwareWallet },
        Suggestion.SUPPORT,
        Suggestion.BACK_UP.takeIf { !settings.backupVerified },
        Suggestion.SECURE.takeIf { !settings.isPinEnabled },
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.INVITE,
    )
}
