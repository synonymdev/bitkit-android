package to.bitkit.ui.screens.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.PubkyRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

private const val TAG = "PubkyChoiceViewModel"

@HiltViewModel
class PubkyChoiceViewModel @Inject constructor(
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
                if (it) _uiState.update { state -> state.copy(navigateToProfile = true) }
            }
        }
    }

    fun clearProfileNavigation() {
        _uiState.update { it.copy(navigateToProfile = false) }
    }

    private fun loadIdentities() {
        viewModelScope.launch {
            val pubkys = pubkyRepo.ringIdentities().getOrElse {
                Logger.warn("Listing ring identities failed", it, context = TAG)
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
