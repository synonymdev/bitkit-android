package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.Milestone
import to.bitkit.models.MilestoneId
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PublicMilestoneRecord
import to.bitkit.models.Toast
import to.bitkit.repositories.MilestoneRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val milestoneRepo: MilestoneRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
        private const val MIN_PUBLISH_LOADING_MS = 800L
    }

    private val _showSignOutDialog = MutableStateFlow(false)
    private val _isSigningOut = MutableStateFlow(false)
    private val _publishingMilestoneId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfileUiState> = combine(
        combine(
            pubkyRepo.profile,
            pubkyRepo.publicKey,
            pubkyRepo.isLoadingProfile,
        ) { profile, publicKey, isLoading ->
            ProfileUiState(
                profile = profile,
                publicKey = publicKey,
                isLoading = isLoading,
            )
        },
        combine(
            pubkyRepo.isAuthenticated,
            _showSignOutDialog,
            _isSigningOut,
            _publishingMilestoneId,
        ) { isAuthenticated, showSignOutDialog, isSigningOut, publishingMilestoneId ->
            ProfileUiState(
                isAuthenticated = isAuthenticated,
                showSignOutDialog = showSignOutDialog,
                isSigningOut = isSigningOut,
                publishingMilestoneId = publishingMilestoneId,
            )
        },
        milestoneRepo.visibleMilestones,
    ) { profileState, authState, milestones ->
        profileState.copy(
            isAuthenticated = authState.isAuthenticated,
            milestones = milestones,
            showSignOutDialog = authState.showSignOutDialog,
            isSigningOut = authState.isSigningOut,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    private val _effects = MutableSharedFlow<ProfileEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        loadProfile()
        loadPublishedMilestones()
    }

    fun loadProfile() {
        viewModelScope.launch { pubkyRepo.loadProfile() }
    }

    fun loadPublishedMilestones() {
        viewModelScope.launch {
            pubkyRepo.loadPublishedMilestones()
                .onSuccess { records ->
                    milestoneRepo.setPublishedIds(records.map { it.milestoneId })
                }
                .onFailure {
                    Logger.warn("Failed to load published milestones", it, context = TAG)
                }
        }
    }

    fun showSignOutConfirmation() {
        _showSignOutDialog.update { true }
    }

    fun dismissSignOutDialog() {
        _showSignOutDialog.update { false }
    }

    fun signOut() {
        viewModelScope.launch {
            _isSigningOut.update { true }
            _showSignOutDialog.update { false }
            pubkyRepo.signOut()
                .onSuccess {
                    _effects.emit(ProfileEffect.SignedOut)
                }
                .onFailure {
                    Logger.error("Sign out failed", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__sign_out_title),
                        description = it.message,
                    )
                }
            _isSigningOut.update { false }
        }
    }

    fun copyPublicKey() {
        val pk = pubkyRepo.publicKey.value ?: return
        context.setClipboardText(pk, context.getString(R.string.profile__public_key))
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
            )
        }
    }

    fun getMilestone(id: MilestoneId): Milestone? = milestoneRepo.getMilestone(id)

    fun publishMilestone(id: MilestoneId) {
        val milestone = milestoneRepo.getMilestone(id) ?: return
        if (!milestone.isUnlocked) return

        val record = PublicMilestoneRecord.fromMilestone(milestone) ?: return
        viewModelScope.launch {
            _publishingMilestoneId.update { id.value }
            yield()
            val startedAt = System.currentTimeMillis()
            try {
                pubkyRepo.publishMilestone(record)
                    .onSuccess {
                        milestoneRepo.markPublished(id)
                        ToastEventBus.send(
                            type = Toast.ToastType.SUCCESS,
                            title = "Published: ${milestone.title}",
                            description = milestone.description,
                        )
                    }
                    .onFailure {
                        Logger.error("Failed to publish milestone '${id.value}'", it, context = TAG)
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = it.message,
                        )
                    }
            } finally {
                val elapsedMs = System.currentTimeMillis() - startedAt
                val remainingMs = MIN_PUBLISH_LOADING_MS - elapsedMs
                if (remainingMs > 0) {
                    delay(remainingMs)
                }
                _publishingMilestoneId.update { null }
            }
        }
    }
}

@Stable
data class ProfileUiState(
    val profile: PubkyProfile? = null,
    val publicKey: String? = null,
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val milestones: ImmutableList<Milestone> = persistentListOf(),
    val showSignOutDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val publishingMilestoneId: String? = null,
)

sealed interface ProfileEffect {
    data object SignedOut : ProfileEffect
}
