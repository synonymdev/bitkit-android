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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
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
        if (_uiState.value.adoptingPubky != null) return
        _uiState.update { it.copy(adoptingPubky = pubky) }
        viewModelScope.launch {
            pubkyRepo.adoptRingIdentity(pubky) { rowProfile(pubky) }
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
            val identities = pubkys.map { RingIdentity(pubky = it, isLookingUp = true) }.toImmutableList()
            _uiState.update { it.copy(isLoading = false, identities = identities) }
            pubkys.forEach { launch { lookUpProfile(it) } }
        }
    }

    private fun rowProfile(pubky: String): PubkyProfile? =
        _uiState.value.identities.firstOrNull { it.pubky == pubky }?.profile

    private suspend fun lookUpProfile(pubky: String) {
        var profile: PubkyProfile? = null
        try {
            profile = pubkyRepo.fetchDisplayProfile(pubky)
                .getOrNull()
                ?.takeIf { PubkyPublicKeyFormat.matches(it.publicKey, pubky) }
                ?.takeIf { currentCoroutineContext().isActive }
        } finally {
            _uiState.update { state ->
                val identities = state.identities.map {
                    if (it.pubky == pubky) it.copy(profile = profile ?: it.profile, isLookingUp = false) else it
                }
                state.copy(identities = identities.toImmutableList())
            }
        }
    }
}

@Stable
data class PubkyChoiceUiState(
    val isLoading: Boolean = true,
    val identities: ImmutableList<RingIdentity> = persistentListOf(),
    val adoptingPubky: String? = null,
    val navigateToProfile: Boolean = false,
)

@Stable
data class RingIdentity(
    val pubky: String,
    val profile: PubkyProfile? = null,
    val isLookingUp: Boolean = false,
) {
    val caption: String get() = PubkyPublicKeyFormat.display(pubky).uppercase()
    val name: String get() = profile?.name?.takeIf { it.isNotBlank() } ?: PubkyPublicKeyFormat.display(pubky)
    val imageUrl: String? get() = profile?.imageUrl
}

sealed interface PubkyChoiceEffect {
    data object NavigateToCreateProfile : PubkyChoiceEffect
    data object NavigateToContactImportOverview : PubkyChoiceEffect
    data object NavigateToPayContacts : PubkyChoiceEffect
}
