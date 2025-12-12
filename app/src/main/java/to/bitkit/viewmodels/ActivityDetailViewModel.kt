package to.bitkit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.TransactionDetails
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.rawId
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
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
    private val lightningRepo: LightningRepo,
) : ViewModel() {
    private val _txDetails = MutableStateFlow<TransactionDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags = _tags.asStateFlow()

    private val _boostSheetVisible = MutableStateFlow(false)
    val boostSheetVisible = _boostSheetVisible.asStateFlow()

    private var activity: Activity? = null
    private var observeJob: Job? = null

    private val _uiState = MutableStateFlow(ActivityDetailUiState())
    val uiState: StateFlow<ActivityDetailUiState> = _uiState.asStateFlow()

    fun setActivity(activity: Activity) {
        this.activity = activity
        loadTags()
    }

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
                        _uiState.update {
                            it.copy(
                                activityLoadState = ActivityLoadState.Error(
                                    context.getString(R.string.wallet__activity_error_not_found)
                                )
                            )
                        }
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
        _uiState.update { it.copy(activityLoadState = ActivityLoadState.Initial) }
        activity = null
        _tags.value = emptyList()
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
                // Keep showing last known state on reload failure
            }
    }

    fun loadTags() {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getActivityTags(id)
                .onSuccess { activityTags ->
                    _tags.value = activityTags
                }
                .onFailure { e ->
                    Logger.error("Failed to load tags for activity $id", e, TAG)
                    _tags.value = emptyList()
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
                .onFailure { e ->
                    Logger.error("Failed to remove tag $tag from activity $id", e, TAG)
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
                .onFailure { e ->
                    Logger.error("Failed to add tag $tag to activity $id", e, TAG)
                }
        }
    }

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch(bgDispatcher) {
            runCatching {
                val transactionDetails = lightningRepo.getTransactionDetails(txid).getOrNull()
                _txDetails.update { transactionDetails }
            }.onFailure { e ->
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

    suspend fun getBoostTxDoesExist(boostTxIds: List<String>): Map<String, Boolean> {
        return activityRepo.getBoostTxDoesExist(boostTxIds)
    }

    suspend fun isCpfpChildTransaction(txId: String): Boolean {
        return activityRepo.isCpfpChildTransaction(txId)
    }

    suspend fun findOrderForTransfer(
        channelId: String?,
        txId: String?,
    ): IBtOrder? = withContext(bgDispatcher) {
        try {
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
        } catch (e: Exception) {
            Logger.warn("Failed to find order for transfer: channelId=$channelId, txId=$txId", e, context = TAG)
            null
        }
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
    )
}
