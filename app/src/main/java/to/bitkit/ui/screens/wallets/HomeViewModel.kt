package to.bitkit.ui.screens.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.models.Suggestion
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appStorage: AppStorage,
    private val walletRepo: WalletRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val suggestions: StateFlow<List<Suggestion>> = createSuggestionsFlow()

    fun removeSuggestion(suggestion: Suggestion) {
        appStorage.addSuggestionToRemovedList(suggestion)
    }

    private fun createSuggestionsFlow(): StateFlow<List<Suggestion>> {
        val removedSuggestions = appStorage.removedSuggestionsFlow
            .map { stringList -> stringList.mapNotNull { it.toSuggestionOrNull() } }

        return combine(
            walletRepo.balanceState,
            removedSuggestions,
            settingsStore.data.map { it.isPinEnabled },
        ) { balanceState, removedList, isPinEnabled ->
            val baseSuggestions = when {
                balanceState.totalLightningSats > 0uL -> { // With Lightning
                    listOfNotNull(
                        Suggestion.BACK_UP,
                        Suggestion.SECURE.takeIf { !isPinEnabled },
                        Suggestion.BUY,
                        Suggestion.SUPPORT,
                        Suggestion.INVITE,
                        Suggestion.QUICK_PAY,
                        Suggestion.SHOP,
                        Suggestion.PROFILE,
                    )
                }

                balanceState.totalOnchainSats > 0uL -> { // Only on chain balance
                    listOfNotNull(
                        Suggestion.BACK_UP,
                        Suggestion.SPEND, //Replace with LIGHTNING_SETTING_UP when the spending balance is confirming
                        Suggestion.SECURE.takeIf { !isPinEnabled },
                        Suggestion.BUY,
                        Suggestion.SUPPORT,
                        Suggestion.INVITE,
                        Suggestion.SHOP,
                        Suggestion.PROFILE,
                    )
                }

                else -> { // Empty wallet
                    listOfNotNull(
                        Suggestion.BUY,
                        Suggestion.SPEND,
                        Suggestion.BACK_UP,
                        Suggestion.SECURE.takeIf { !isPinEnabled },
                        Suggestion.SUPPORT,
                        Suggestion.INVITE,
                        Suggestion.PROFILE,
                    )
                }
            }
            //TODO REMOVE PROFILE CARD IF THE USER ALREADY HAS one
            return@combine baseSuggestions.filterNot { it in removedList }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
}
