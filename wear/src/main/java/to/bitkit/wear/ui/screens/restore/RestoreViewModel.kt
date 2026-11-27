package to.bitkit.wear.ui.screens.restore

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.security.Bip39
import to.bitkit.wear.security.Bip39Validation
import to.bitkit.wear.security.WalletSeed
import javax.inject.Inject

/**
 * Restores a wallet from a recovery phrase typed one word per screen. It is shared by the restore screens and holds
 * the words only in memory, until the wallet is saved or the wearer leaves the flow; they are never logged or saved
 * to instance state.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val bip39: Bip39,
    private val walletRepo: WalletRepo,
) : ViewModel() {
    companion object {
        /** The recovery phrase lengths Bitkit creates and the watch restores. */
        val WORD_COUNTS = persistentListOf(12, 24)

        const val MAX_PASSPHRASE_LENGTH = 256
    }

    private val _uiState = MutableStateFlow(RestoreUiState())
    val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

    private val _effects = Channel<RestoreEffect>(Channel.BUFFERED)
    val effects: Flow<RestoreEffect> = _effects.receiveAsFlow()

    /** Starts typing a phrase of [count] words. Words typed earlier stay when the count is the same. */
    fun onWordCountSelect(count: Int) {
        if (count !in WORD_COUNTS) return
        _uiState.update {
            if (it.entries.size == count) it else it.copy(entries = List(count) { WordEntry() }.toImmutableList())
        }
        _effects.trySend(RestoreEffect.ShowWord(0))
    }

    fun onWordChange(index: Int, text: String) = setEntry(index, Bip39.sanitize(text))

    fun onSuggestionSelect(index: Int, word: String) {
        setEntry(index, word)
        onWordConfirm(index)
    }

    /** Moves on from the word at [index] when it is a wordlist word; the last word checks the whole phrase. */
    fun onWordConfirm(index: Int) {
        val entries = _uiState.value.entries
        val entry = entries.getOrNull(index) ?: return
        if (!entry.isWord) return
        if (index < entries.lastIndex) {
            _effects.trySend(RestoreEffect.ShowWord(index + 1))
            return
        }
        _effects.trySend(effectFor(validate()))
    }

    fun onPassphraseAdd() {
        _uiState.update { it.copy(isPassphraseEditing = true, isSaveFailed = false) }
    }

    fun onPassphraseChange(text: String) {
        _uiState.update { it.copy(passphrase = text.take(MAX_PASSPHRASE_LENGTH)) }
    }

    fun onPassphraseSkip() = save(passphrase = null)

    /** An empty passphrase is no passphrase. It is used exactly as typed, spaces included. */
    fun onPassphraseConfirm() = save(passphrase = _uiState.value.passphrase.ifEmpty { null })

    private fun save(passphrase: String?) {
        if (_uiState.value.isSaving) return
        val validation = validate()
        if (validation != Bip39Validation.Valid) {
            _effects.trySend(effectFor(validation))
            return
        }
        _uiState.update { it.copy(isSaving = true, isSaveFailed = false) }
        viewModelScope.launch {
            val mnemonic = _uiState.value.entries.joinToString(" ") { it.input }
            walletRepo.restore(WalletSeed(mnemonic, passphrase))
                .onSuccess {
                    _uiState.update { RestoreUiState() }
                    _effects.trySend(RestoreEffect.Restored)
                }
                .onFailure { _uiState.update { it.copy(isSaving = false, isSaveFailed = true) } }
        }
    }

    private fun validate(): Bip39Validation = bip39.validate(_uiState.value.entries.map { it.input })

    private fun effectFor(validation: Bip39Validation): RestoreEffect = when (validation) {
        Bip39Validation.Valid -> RestoreEffect.ShowPassphrase
        is Bip39Validation.UnknownWord -> RestoreEffect.ShowWord(validation.index)
        Bip39Validation.InvalidChecksum -> RestoreEffect.ShowChecksumError
    }

    private fun setEntry(index: Int, input: String) {
        val entry = WordEntry(
            input = input,
            suggestions = bip39.suggestions(input).toImmutableList(),
            isWord = bip39.isWord(input),
        )
        _uiState.update {
            if (index !in it.entries.indices || it.isSaving) return@update it
            it.copy(entries = it.entries.toMutableList().apply { this[index] = entry }.toImmutableList())
        }
    }

    override fun onCleared() {
        _uiState.update { RestoreUiState() }
    }
}

/** [toString] never prints the words or the passphrase. */
@Immutable
data class RestoreUiState(
    val entries: ImmutableList<WordEntry> = persistentListOf(),
    val passphrase: String = "",
    val isPassphraseEditing: Boolean = false,
    val isSaving: Boolean = false,
    val isSaveFailed: Boolean = false,
) {
    override fun toString(): String = "RestoreUiState(***)"
}

/** What is typed for one word of the phrase, and the wordlist words it could become. */
@Immutable
data class WordEntry(
    val input: String = "",
    val suggestions: ImmutableList<String> = persistentListOf(),
    val isWord: Boolean = false,
) {
    override fun toString(): String = "WordEntry(***)"
}

sealed interface RestoreEffect {
    data class ShowWord(val index: Int) : RestoreEffect
    data object ShowChecksumError : RestoreEffect
    data object ShowPassphrase : RestoreEffect
    data object Restored : RestoreEffect
}
