package to.bitkit.ui.screens.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.AppStorage
import to.bitkit.models.Suggestion
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appStorage: AppStorage,
    private val walletRepo: WalletRepo
) : ViewModel() {

    private val _suggestions = MutableStateFlow(listOf<Suggestion>())
    val suggestions = _suggestions.asStateFlow()

    init {
        setupSuggestionList()
    }

    fun removeSuggestion(suggestion: Suggestion) {
        appStorage.addSuggestionToRemovedList(suggestion)
        _suggestions.update { it.filterNot { it == suggestion } }
    }

    private fun setupSuggestionList() {
        viewModelScope.launch {
            val removedList = appStorage.getRemovedSuggestionList().mapNotNull { it.toSuggestionOrNull() }

            walletRepo.balanceState.collect { balanceState ->
                when {
                    balanceState.totalLightningSats > 0uL -> { //With Lightning
                        val filteredSuggestions = listOf(
                            Suggestion.BACK_UP,
                            Suggestion.SECURE,
                            Suggestion.BUY,
                            Suggestion.SUPPORT,
                            Suggestion.INVITE,
                            Suggestion.QUICK_PAY,
                            Suggestion.SHOP,
                            Suggestion.PROFILE,
                        )
                        _suggestions.update { filteredSuggestions }
                    }

                    balanceState.totalOnchainSats > 0uL -> { //Only on chain balance
                        val filteredSuggestions = listOf(
                            Suggestion.BACK_UP,
                            Suggestion.SPEND,
                            Suggestion.SECURE,
                            Suggestion.BUY,
                            Suggestion.SUPPORT,
                            Suggestion.INVITE,
                            Suggestion.SHOP,
                            Suggestion.PROFILE,
                        ).filterNot { it in removedList }
                        _suggestions.update { filteredSuggestions }
                    }

                    else -> { //Empty wallet
                        val filteredSuggestions = listOf(
                            Suggestion.BUY,
                            Suggestion.SPEND,
                            Suggestion.BACK_UP,
                            Suggestion.SECURE,
                            Suggestion.SUPPORT,
                            Suggestion.INVITE,
                            Suggestion.PROFILE,
                        ).filterNot { it in removedList }
                        _suggestions.update { filteredSuggestions }
                    }
                }
            }
        }
    }


}
