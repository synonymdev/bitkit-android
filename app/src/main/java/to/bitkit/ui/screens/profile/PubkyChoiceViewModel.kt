package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

private const val TAG = "PubkyChoiceViewModel"

@HiltViewModel
class PubkyChoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PubkyChoiceUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyChoiceEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        loadIdentities()
        viewModelScope.launch {
            pubkyRepo.isAuthenticated.collectLatest {
                if (it && _uiState.value.adoptingPubky == null) {
                    _uiState.update { state -> state.copy(navigateToProfile = true) }
                }
            }
        }
    }

    fun onIdentityClick(pubky: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(adoptingPubky = pubky) }
            pubkyRepo.adoptRingIdentity(pubky)
                .onSuccess { hasProfile ->
                    if (hasProfile) {
                        pubkyRepo.prepareImport().onFailure {
                            Logger.error("Failed to prepare contact import", it, context = TAG)
                            ToastEventBus.send(
                                type = Toast.ToastType.ERROR,
                                title = context.getString(R.string.common__error),
                                description = it.message,
                            )
                        }
                    }
                    _uiState.update { it.copy(adoptingPubky = null) }
                    val effect = when {
                        !hasProfile -> PubkyChoiceEffect.NavigateToCreateProfile
                        pubkyRepo.pendingImportContacts.value.isEmpty() -> PubkyChoiceEffect.NavigateToPayContacts
                        else -> PubkyChoiceEffect.NavigateToContactImportOverview
                    }
                    _effects.emit(effect)
                }
                .onFailure {
                    Logger.error("Failed to adopt ring identity", it, context = TAG)
                    _uiState.update { state -> state.copy(adoptingPubky = null) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    fun clearProfileNavigation() {
        _uiState.update { it.copy(navigateToProfile = false) }
    }

    private fun loadIdentities() {
        viewModelScope.launch {
            val pubkys = pubkyRepo.ringIdentities().getOrElse {
                Logger.warn("Failed to list ring identities", it, context = TAG)
                persistentListOf()
            }
            val identities = pubkys.map { pubky ->
                val profile = pubkyRepo.fetchRemoteProfile(pubky).getOrNull()
                val truncatedKey = PubkyPublicKeyFormat.display(pubky)
                RingIdentity(
                    pubky = pubky,
                    caption = truncatedKey.uppercase(),
                    name = profile?.name?.takeIf { it.isNotBlank() } ?: truncatedKey,
                    imageUrl = profile?.imageUrl,
                )
            }.toImmutableList()

            _uiState.update { it.copy(isLoading = false, identities = identities) }
        }
    }
}

@Immutable
data class PubkyChoiceUiState(
    val isLoading: Boolean = true,
    val identities: ImmutableList<RingIdentity> = persistentListOf(),
    val adoptingPubky: String? = null,
    val navigateToProfile: Boolean = false,
)

@Immutable
data class RingIdentity(
    val pubky: String,
    val caption: String,
    val name: String,
    val imageUrl: String?,
)

sealed interface PubkyChoiceEffect {
    data object NavigateToCreateProfile : PubkyChoiceEffect
    data object NavigateToContactImportOverview : PubkyChoiceEffect
    data object NavigateToPayContacts : PubkyChoiceEffect
}
