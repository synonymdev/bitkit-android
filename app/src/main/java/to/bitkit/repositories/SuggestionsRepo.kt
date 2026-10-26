package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.BgDispatcher
import to.bitkit.models.Suggestion
import to.bitkit.models.TransferType
import to.bitkit.models.toSuggestionOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuggestionsRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
    private val transferRepo: TransferRepo,
    private val pubkyRepo: PubkyRepo,
    private val hwWalletRepo: HwWalletRepo,
) {
    companion object {
        private const val MAX_SUGGESTIONS = 4
    }

    val suggestionsFlow: Flow<List<Suggestion>> = combine(
        walletRepo.balanceState,
        settingsStore.data,
        transferRepo.activeTransfers,
        pubkyRepo.isAuthenticated,
        hwWalletRepo.wallets,
    ) { balanceState, settings, transfers, profileAuthenticated, hardwareWallets ->
        val hasHardwareWallet = hardwareWallets.isNotEmpty()
        val baseSuggestions = when {
            balanceState.totalLightningSats > 0uL ->
                spendingSuggestions(settings, profileAuthenticated, hasHardwareWallet)
            balanceState.totalOnchainSats > 0uL ->
                savingsOnlySuggestions(settings, transfers, profileAuthenticated, hasHardwareWallet)
            else -> emptyWalletSuggestions(settings, transfers, profileAuthenticated, hasHardwareWallet)
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

    private fun spendingSuggestions(
        settings: SettingsData,
        profileAuthenticated: Boolean,
        hasHardwareWallet: Boolean,
    ) = listOfNotNull(
        Suggestion.QUICK_PAY.takeIf { !settings.isQuickPayEnabled },
        Suggestion.NOTIFICATIONS.takeIf { !settings.notificationsGranted },
        Suggestion.HARDWARE.takeIf { !hasHardwareWallet },
        Suggestion.SHOP,
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.SUPPORT,
        Suggestion.INVITE,
        Suggestion.BUY,
    )

    private fun savingsOnlySuggestions(
        settings: SettingsData,
        transfers: List<TransferEntity>,
        profileAuthenticated: Boolean,
        hasHardwareWallet: Boolean,
    ) = listOfNotNull(
        Suggestion.BACK_UP.takeIf { !settings.backupVerified },
        Suggestion.SECURE.takeIf { !settings.isPinEnabled },
        Suggestion.LIGHTNING.takeIf {
            transfers.all { it.type != TransferType.TO_SPENDING }
        },
        Suggestion.HARDWARE.takeIf { !hasHardwareWallet },
        Suggestion.SUPPORT,
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.INVITE,
        Suggestion.BUY,
    )

    private fun emptyWalletSuggestions(
        settings: SettingsData,
        transfers: List<TransferEntity>,
        profileAuthenticated: Boolean,
        hasHardwareWallet: Boolean,
    ) = listOfNotNull(
        Suggestion.BUY,
        Suggestion.LIGHTNING.takeIf {
            transfers.all { it.type != TransferType.TO_SPENDING }
        },
        Suggestion.HARDWARE.takeIf { !hasHardwareWallet },
        Suggestion.SUPPORT,
        Suggestion.BACK_UP.takeIf { !settings.backupVerified },
        Suggestion.SECURE.takeIf { !settings.isPinEnabled },
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.INVITE,
    )
}
