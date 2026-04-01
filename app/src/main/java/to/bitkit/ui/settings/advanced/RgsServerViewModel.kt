package to.bitkit.ui.settings.advanced

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.repositories.LightningRepo
import java.net.URI
import javax.inject.Inject

@HiltViewModel
class RgsServerViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val settingsStore: SettingsStore,
    private val lightningRepo: LightningRepo,
) : ViewModel() {

    companion object {
        private val HOSTNAME_PATTERN = Regex(
            "^([a-z\\d]([a-z\\d-]*[a-z\\d])*\\.)+[a-z]{2,}|(\\d{1,3}\\.){3}\\d{1,3}$",
            RegexOption.IGNORE_CASE,
        )
        private val PATH_PATTERN = Regex("^(/[a-zA-Z\\d_.~%+-]*)*$")
    }

    private val _uiState = MutableStateFlow(RgsServerUiState())
    val uiState: StateFlow<RgsServerUiState> = _uiState.asStateFlow()

    init {
        observeState()
    }

    private fun observeState() {
        viewModelScope.launch(bgDispatcher) {
            settingsStore.data.map { it.rgsServerUrl }.distinctUntilChanged()
                .collect { rgsServerUrl ->
                    _uiState.update {
                        val newState = it.copy(
                            connectedRgsUrl = rgsServerUrl,
                            rgsUrl = rgsServerUrl.orEmpty(),
                        )
                        computeState(newState)
                    }
                }
        }
    }

    fun setRgsUrl(url: String) {
        _uiState.update {
            val newState = it.copy(rgsUrl = url.trim())
            computeState(newState)
        }
    }

    fun resetToDefault() {
        val defaultUrl = Env.ldkRgsServerUrl ?: ""
        _uiState.update {
            val newState = it.copy(rgsUrl = defaultUrl)
            computeState(newState)
        }
    }

    fun onClickConnect() {
        val currentState = _uiState.value
        val url = currentState.rgsUrl

        if (url.isBlank() || !isValidURL(url)) {
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(bgDispatcher) {
            lightningRepo.restartWithRgsServer(url)
                .onSuccess {
                    _uiState.update {
                        val newState = it.copy(
                            isLoading = false,
                            connectionResult = Result.success(Unit),
                        )
                        computeState(newState)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            connectionResult = Result.failure(error),
                        )
                    }
                }
        }
    }

    fun onScan(data: String) = setRgsUrl(data)

    fun clearConnectionResult() = _uiState.update { it.copy(connectionResult = null) }

    private fun computeState(state: RgsServerUiState): RgsServerUiState {
        val hasEdited = state.rgsUrl != state.connectedRgsUrl.orEmpty()
        val canConnect = hasEdited && state.rgsUrl.isNotBlank() && isValidURL(state.rgsUrl)
        val canReset = state.rgsUrl != Env.ldkRgsServerUrl.orEmpty()

        return state.copy(
            hasEdited = hasEdited,
            canConnect = canConnect,
            canReset = canReset,
        )
    }

    private fun isValidURL(data: String): Boolean {
        val normalized = if (!data.startsWith("http://") && !data.startsWith("https://")) {
            "https://$data"
        } else {
            data
        }

        return try {
            val uri = URI(normalized)
            val hostname = uri.host ?: return false

            if (Env.isDebug && hostname == "localhost") return true

            if (!HOSTNAME_PATTERN.matches(hostname)) return false

            val path = uri.path.orEmpty()
            path.isEmpty() || PATH_PATTERN.matches(path)
        } catch (_: Throwable) {
            false
        }
    }
}

@Stable
data class RgsServerUiState(
    val connectedRgsUrl: String? = null,
    val rgsUrl: String = "",
    val isLoading: Boolean = false,
    val connectionResult: Result<Unit>? = null,
    val hasEdited: Boolean = false,
    val canConnect: Boolean = false,
    val canReset: Boolean = false,
)
