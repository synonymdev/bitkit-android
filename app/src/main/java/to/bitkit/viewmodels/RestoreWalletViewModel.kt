package to.bitkit.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.services.core.Bip39Service
import javax.inject.Inject

private const val WORDS_MIN = 12
private const val WORDS_MAX = 24

@HiltViewModel
class RestoreWalletViewModel @Inject constructor(
    private val bip39Service: Bip39Service,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RestoreWalletUiState())
    val uiState: StateFlow<RestoreWalletUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(focusedIndex = 0) }
        recomputeValidationState()
    }

    private fun recomputeValidationState() = viewModelScope.launch {
        val currentState = _uiState.value
        val checksumError = currentState.isChecksumErrorVisible()
        val buttonsEnabled = currentState.areButtonsEnabled()

        _uiState.update {
            it.copy(
                checksumErrorVisible = checksumError,
                areButtonsEnabled = buttonsEnabled
            )
        }
    }

    fun onChangeWord(index: Int, value: String) {
        if (value.contains(Regex("\\s"))) {
            handlePastedWords(value)
        } else {
            updateWordValidity(index, value)
            updateSuggestions(value, _uiState.value.focusedIndex)
            _uiState.update { it.copy(scrollToFieldIndex = index) }
        }
    }

    fun onChangeWordFocus(index: Int, focused: Boolean) {
        if (focused) {
            _uiState.update {
                it.copy(
                    focusedIndex = index,
                    scrollToFieldIndex = index
                )
            }
            updateSuggestions(_uiState.value.words[index], index)
        } else if (_uiState.value.focusedIndex == index) {
            _uiState.update {
                it.copy(
                    focusedIndex = null,
                    suggestions = persistentListOf()
                )
            }
        }
    }

    fun onSelectSuggestion(suggestion: String) {
        _uiState.value.focusedIndex?.let { index ->
            updateWordValidity(index, suggestion)
            _uiState.update { it.copy(suggestions = persistentListOf()) }
        }
    }

    fun onAdvancedClick() {
        _uiState.update {
            it.copy(
                showingPassphrase = !it.showingPassphrase,
                bip39Passphrase = ""
            )
        }
    }

    fun onChangePassphrase(passphrase: String) = _uiState.update { it.copy(bip39Passphrase = passphrase) }

    fun onBackspaceInEmpty(index: Int) {
        if (index > 0) {
            _uiState.update { it.copy(focusedIndex = index - 1) }
        }
    }

    fun onKeyboardDismiss() = _uiState.update { it.copy(shouldDismissKeyboard = false) }

    fun onScrollComplete() = _uiState.update { it.copy(scrollToFieldIndex = null) }

    private fun handlePastedWords(pastedText: String) = viewModelScope.launch {
        val separators = Regex("\\s+") // any whitespace chars to account for different sources like password managers
        val pastedWords = pastedText
            .split(separators)
            .filter { it.isNotBlank() }
        if (pastedWords.size == WORDS_MIN || pastedWords.size == WORDS_MAX) {
            val invalidIndices = pastedWords.withIndex()
                .filter { !bip39Service.isValidWord(it.value) }
                .map { it.index }
                .toSet()

            val newWords = _uiState.value.words.toMutableList().apply {
                pastedWords.forEachIndexed { index, word -> this[index] = word }
                for (index in pastedWords.size until WORDS_MAX) {
                    this[index] = ""
                }
            }

            _uiState.update {
                it.copy(
                    words = newWords.toImmutableList(),
                    invalidWordIndices = invalidIndices.toImmutableSet(),
                    is24Words = pastedWords.size == WORDS_MAX,
                    shouldDismissKeyboard = invalidIndices.isEmpty(),
                    focusedIndex = null,
                    suggestions = persistentListOf(),
                )
            }
            recomputeValidationState()
        }
    }

    private fun updateWordValidity(index: Int, value: String) = viewModelScope.launch {
        val newWords = _uiState.value.words.toMutableList().apply {
            this[index] = value
        }

        val newInvalidIndices = _uiState.value.invalidWordIndices.toMutableSet()
        if (!bip39Service.isValidWord(value) && value.isNotEmpty()) {
            newInvalidIndices.add(index)
        } else {
            newInvalidIndices.remove(index)
        }

        _uiState.update {
            it.copy(
                words = newWords.toImmutableList(),
                invalidWordIndices = newInvalidIndices.toImmutableSet(),
            )
        }
        recomputeValidationState()
    }

    private fun updateSuggestions(input: String, index: Int?) = viewModelScope.launch {
        if (index == null || input.length < 2) {
            _uiState.update { it.copy(suggestions = persistentListOf()) }
            return@launch
        }

        val suggestions = bip39Service.getSuggestions(input.lowercase(), 3u)
        val filtered = if (suggestions.size == 1 && suggestions.firstOrNull() == input) {
            emptyList()
        } else {
            suggestions
        }

        _uiState.update { it.copy(suggestions = filtered.toImmutableList()) }
    }

    private suspend fun RestoreWalletUiState.areButtonsEnabled(): Boolean {
        val activeWords = words.subList(0, wordCount)
        return activeWords.none { it.isBlank() } &&
            invalidWordIndices.isEmpty() &&
            !isChecksumErrorVisible()
    }

    private suspend fun RestoreWalletUiState.isChecksumErrorVisible(): Boolean {
        val activeWords = words.subList(0, wordCount)
        return activeWords.none { it.isBlank() } &&
            invalidWordIndices.isEmpty() &&
            !validateBip39Checksum(activeWords)
    }

    private suspend fun validateBip39Checksum(wordList: List<String>): Boolean {
        if (!bip39Service.isValidMnemonicSize(wordList)) return false
        if (wordList.any { !bip39Service.isValidWord(it) }) return false

        return bip39Service.validateMnemonic(wordList.joinToString(" ")).isSuccess
    }
}

@Immutable
data class RestoreWalletUiState(
    val words: ImmutableList<String> = List(WORDS_MAX) { "" }.toImmutableList(),
    val invalidWordIndices: ImmutableSet<Int> = persistentSetOf(),
    val suggestions: ImmutableList<String> = persistentListOf(),
    val focusedIndex: Int? = null,
    val bip39Passphrase: String = "",
    val showingPassphrase: Boolean = false,
    val is24Words: Boolean = false,
    val shouldDismissKeyboard: Boolean = false,
    val scrollToFieldIndex: Int? = null,
    val checksumErrorVisible: Boolean = false,
    val areButtonsEnabled: Boolean = false,
) {
    val wordCount: Int get() = if (is24Words) WORDS_MAX else WORDS_MIN
    val wordsPerColumn: Int get() = if (is24Words) WORDS_MIN else 6

    val bip39Mnemonic: String get() = words.subList(0, wordCount).joinToString(" ").trim()
}
