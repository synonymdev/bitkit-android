package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.sharing.SharedPubkyContract
import to.bitkit.data.sharing.SharedPubkyIdentity
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class PubkyChoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "PubkyChoiceViewModel"
    }

    private val _uiState = MutableStateFlow(PubkyChoiceUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyChoiceEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        refreshRingIdentities()
    }

    fun refreshRingIdentities() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true) }
            val choices = pubkyRepo.discoverRingIdentities()
                .map { identities ->
                    coroutineScope {
                        identities.map { identity ->
                            async {
                                val bitkitPubky = SharedPubkyContract.toBitkitPubky(identity.pubky)
                                val profile = pubkyRepo.fetchRemoteProfile(bitkitPubky)
                                    .getOrNull()
                                    ?: PubkyProfile.placeholder(bitkitPubky)
                                SharedPubkyChoice(
                                    protocolVersion = identity.protocolVersion,
                                    sourcePackage = identity.sourcePackage,
                                    pubky = identity.pubky,
                                    profile = profile,
                                )
                            }
                        }.awaitAll()
                    }
                }
                .onFailure {
                    Logger.info("Found no available Pubky Ring identities", context = TAG)
                }
                .getOrDefault(emptyList())
                .sortedWith(compareBy({ it.profile.name.lowercase() }, { it.pubky }))
                .toImmutableList()
            _uiState.update { it.copy(isDiscovering = false, identities = choices) }
        }
    }

    fun selectRingIdentity(choice: SharedPubkyChoice) {
        if (_uiState.value.selectedPubky != null) return
        _uiState.update { it.copy(selectedPubky = choice.pubky) }

        viewModelScope.launch {
            pubkyRepo.adoptRingIdentity(choice.toIdentity())
                .onSuccess {
                    pubkyRepo.prepareImport()
                        .onSuccess {
                            _uiState.update { state -> state.copy(selectedPubky = null) }
                            if (pubkyRepo.pendingImportContacts.value.isEmpty()) {
                                _effects.emit(PubkyChoiceEffect.NavigateToPayContacts)
                            } else {
                                _effects.emit(PubkyChoiceEffect.NavigateToContactImportOverview)
                            }
                        }
                        .onFailure {
                            handleSelectionFailure("Preparing shared profile failed", it)
                        }
                }
                .onFailure {
                    handleSelectionFailure("Connecting shared profile failed", it)
                }
        }
    }

    private suspend fun handleSelectionFailure(message: String, error: Throwable) {
        Logger.error(message, error, context = TAG)
        _uiState.update { it.copy(selectedPubky = null) }
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.profile__choice_error),
            description = error.message,
        )
        refreshRingIdentities()
    }
}

@Stable
data class PubkyChoiceUiState(
    val isDiscovering: Boolean = false,
    val identities: ImmutableList<SharedPubkyChoice> = persistentListOf(),
    val selectedPubky: String? = null,
)

@Stable
data class SharedPubkyChoice(
    val protocolVersion: Int,
    val sourcePackage: String,
    val pubky: String,
    val profile: PubkyProfile,
) {
    fun toIdentity() = SharedPubkyIdentity(
        protocolVersion = protocolVersion,
        sourcePackage = sourcePackage,
        pubky = pubky,
    )
}

sealed interface PubkyChoiceEffect {
    data object NavigateToContactImportOverview : PubkyChoiceEffect
    data object NavigateToPayContacts : PubkyChoiceEffect
}
