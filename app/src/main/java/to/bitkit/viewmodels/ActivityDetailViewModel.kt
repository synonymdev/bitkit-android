package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.TransactionDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.rawId
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val settingsStore: SettingsStore,
    private val blocktankRepo: BlocktankRepo,
    private val hwWalletRepo: HwWalletRepo,
) : ViewModel() {
    private val _txDetails = MutableStateFlow<TransactionDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _tags = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val tags = _tags.asStateFlow()

    private val _boostSheetVisible = MutableStateFlow(false)
    val boostSheetVisible = _boostSheetVisible.asStateFlow()

    private var activity: Activity? = null
    private var observeJob: Job? = null

    private val _uiState = MutableStateFlow(ActivityDetailUiState())
    val uiState: StateFlow<ActivityDetailUiState> = _uiState.asStateFlow()

    fun loadActivity(activityId: String) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(activityLoadState = ActivityLoadState.Loading) }

            activityRepo.getActivity(activityId)
                .onSuccess { activity ->
                    if (activity != null) {
                        this@ActivityDetailViewModel.activity = activity
                        _uiState.update { it.copy(activityLoadState = ActivityLoadState.Success(activity)) }
                        loadTags()
                        observeActivityChanges(activityId)
                    } else {
                        loadHwWalletActivity(activityId)
                    }
                }
                .onFailure { e ->
                    Logger.error("Failed to load activity $activityId", e, TAG)
                    _uiState.update {
                        it.copy(
                            activityLoadState = ActivityLoadState.Error(
                                e.message ?: context.getString(R.string.wallet__activity_error_load_failed)
                            )
                        )
                    }
                }
        }
    }

    fun clearActivityState() {
        observeJob?.cancel()
        observeJob = null
        _uiState.update { it.copy(activityLoadState = ActivityLoadState.Initial, isHardwareActivity = false) }
        activity = null
        _tags.update { persistentListOf() }
    }

    private fun loadHwWalletActivity(activityId: String) {
        val hwActivity = hwWalletRepo.activities.value.find { it.rawId() == activityId }
        if (hwActivity != null) {
            activity = hwActivity
            _uiState.update {
                it.copy(activityLoadState = ActivityLoadState.Success(hwActivity), isHardwareActivity = true)
            }
            observeHwWalletActivityChanges(activityId)
        } else {
            _uiState.update {
                it.copy(
                    activityLoadState = ActivityLoadState.Error(
                        context.getString(R.string.wallet__activity_error_not_found)
                    )
                )
            }
        }
    }

    private fun observeHwWalletActivityChanges(activityId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(bgDispatcher) {
            hwWalletRepo.activities.collect { activities ->
                val updatedActivity = activities.find { it.rawId() == activityId } ?: return@collect
                activity = updatedActivity
                _uiState.update {
                    it.copy(
                        activityLoadState = ActivityLoadState.Success(updatedActivity),
                        isHardwareActivity = true,
                    )
                }
            }
        }
    }

    private fun observeActivityChanges(activityId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(bgDispatcher) {
            activityRepo.activitiesChanged.collect {
                reloadActivity(activityId)
            }
        }
    }

    private suspend fun reloadActivity(activityId: String) {
        activityRepo.getActivity(activityId)
            .onSuccess { updatedActivity ->
                if (updatedActivity != null) {
                    activity = updatedActivity
                    _uiState.update {
                        it.copy(activityLoadState = ActivityLoadState.Success(updatedActivity))
                    }
                    loadTags()
                }
            }
            .onFailure { error ->
                Logger.warn("Failed to reload activity $activityId", error, context = TAG)
                // Keep showing the last known state on reload failure
            }
    }

    fun loadTags() {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getActivityTags(id)
                .onSuccess { activityTags ->
                    _tags.update { activityTags.toImmutableList() }
                }
                .onFailure {
                    Logger.error("Failed to load tags for activity $id", it, TAG)
                    _tags.update { persistentListOf() }
                }
        }
    }

    fun removeTag(tag: String) {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            activityRepo.removeTagsFromActivity(id, listOf(tag))
                .onSuccess {
                    loadTags()
                }
                .onFailure {
                    Logger.error("Failed to remove tag $tag from activity $id", it, TAG)
                }
        }
    }

    fun addTag(tag: String) {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            activityRepo.addTagsToActivity(id, listOf(tag))
                .onSuccess {
                    settingsStore.addLastUsedTag(tag)
                    loadTags()
                }
                .onFailure {
                    Logger.error("Failed to add tag $tag to activity $id", it, TAG)
                }
        }
    }

    fun detachContact() {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            activityRepo.clearContact(
                forPaymentId = id,
                syncLdkPayments = false,
            ).onSuccess {
                reloadActivity(id)
            }.onFailure {
                Logger.error("Failed to detach contact for activity '$id'", it, context = TAG)
            }
        }
    }

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getTransactionDetails(txid)
                .onSuccess { transactionDetails ->
                    _txDetails.update { transactionDetails }
                }
                .onFailure { e ->
                    Logger.error("fetchTransactionDetails error", e, context = TAG)
                    _txDetails.update { null }
                }
        }
    }

    fun clearTransactionDetails() {
        _txDetails.update { null }
    }

    fun onClickBoost() {
        _boostSheetVisible.update { true }
    }

    fun onDismissBoostSheet() {
        _boostSheetVisible.update { false }
    }

    suspend fun getBoostTxDoesExist(boostTxIds: List<String>): ImmutableMap<String, Boolean> =
        activityRepo.getBoostTxDoesExist(boostTxIds).toImmutableMap()

    suspend fun isCpfpChildTransaction(txId: String): Boolean {
        return activityRepo.isCpfpChildTransaction(txId)
    }

    suspend fun findOrderForTransfer(
        channelId: String?,
        txId: String?,
    ): IBtOrder? = withContext(bgDispatcher) {
        runCatching {
            val orders = blocktankRepo.blocktankState.value.orders

            if (channelId != null) {
                orders.find { it.id == channelId }?.let { return@withContext it }
            }

            if (txId != null) {
                orders.firstOrNull { order ->
                    order.payment?.onchain?.transactions?.any { it.txId == txId } == true
                }?.let { return@withContext it }
            }

            null
        }.onFailure {
            Logger.warn("Failed to find order for transfer: channelId='$channelId', txId='$txId'", it, context = TAG)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "ActivityDetailViewModel"
    }

    sealed interface ActivityLoadState {
        data object Initial : ActivityLoadState
        data object Loading : ActivityLoadState
        data class Success(val activity: Activity) : ActivityLoadState
        data class Error(val message: String) : ActivityLoadState
    }

    data class ActivityDetailUiState(
        val activityLoadState: ActivityLoadState = ActivityLoadState.Initial,
        val isHardwareActivity: Boolean = false,
    )
}
