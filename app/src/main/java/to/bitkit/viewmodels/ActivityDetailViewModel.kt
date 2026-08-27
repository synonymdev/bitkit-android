package to.bitkit.viewmodels

import android.content.Context
import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.isFromHardwareWallet
import to.bitkit.ext.rawId
import to.bitkit.ext.walletId
import to.bitkit.models.WalletScope
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val settingsStore: SettingsStore,
    private val blocktankRepo: BlocktankRepo,
    private val hwWalletRepo: HwWalletRepo,
    private val transferRepo: TransferRepo,
) : ViewModel() {
    private val _txDetails = MutableStateFlow<TransactionDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _isTxDetailsLoading = MutableStateFlow(false)
    val isTxDetailsLoading = _isTxDetailsLoading.asStateFlow()

    private val _isTxDetailsUnavailable = MutableStateFlow(false)
    val isTxDetailsUnavailable = _isTxDetailsUnavailable.asStateFlow()

    private val _tags = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val tags = _tags.asStateFlow()

    private val _boostSheetVisible = MutableStateFlow(false)
    val boostSheetVisible = _boostSheetVisible.asStateFlow()

    private var activity: Activity? = null
    private var requestedWalletId: String? = null
    private var observeJob: Job? = null
    private val currentWalletId: String
        get() = activity?.walletId() ?: requestedWalletId ?: WalletScope.default

    private val _uiState = MutableStateFlow(ActivityDetailUiState())
    val uiState: StateFlow<ActivityDetailUiState> = _uiState.asStateFlow()

    fun loadActivity(activityId: String, walletId: String? = null) {
        requestedWalletId = walletId
        activity = null
        val resolvedWalletId = currentWalletId
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(activityLoadState = ActivityLoadState.Loading) }

            activityRepo.getActivity(activityId, resolvedWalletId)
                .onSuccess { activity ->
                    if (activity != null) {
                        this@ActivityDetailViewModel.activity = activity
                        _uiState.update {
                            it.copy(
                                activityLoadState = ActivityLoadState.Success(activity),
                                isHardwareActivity = activity.isFromHardwareWallet(),
                            )
                        }
                        loadTags()
                        observeActivityChanges(activityId, resolvedWalletId)
                    } else {
                        loadHwWalletActivity(activityId, resolvedWalletId)
                    }
                }
                .onFailure {
                    Logger.warn("Failed to load activity '$activityId'", it, context = TAG)
                    loadHwWalletActivity(activityId, resolvedWalletId, it)
                }
        }
    }

    fun clearActivityState() {
        observeJob?.cancel()
        observeJob = null
        _uiState.update { it.copy(activityLoadState = ActivityLoadState.Initial, isHardwareActivity = false) }
        activity = null
        requestedWalletId = null
        _tags.update { persistentListOf() }
        clearTransactionDetails()
    }

    private fun loadHwWalletActivity(
        activityId: String,
        walletId: String,
        coreError: Throwable? = null,
        observeChanges: Boolean = true,
    ) {
        val hwActivity = hwWalletRepo.activities.value.find {
            it.rawId() == activityId && it.walletId() == walletId
        }
        if (hwActivity != null) {
            activity = hwActivity
            _uiState.update {
                it.copy(activityLoadState = ActivityLoadState.Success(hwActivity), isHardwareActivity = true)
            }
            if (observeChanges) observeActivityChanges(activityId, walletId)
        } else {
            _uiState.update {
                it.copy(
                    activityLoadState = ActivityLoadState.Error(
                        coreError?.message ?: context.getString(R.string.wallet__activity_error_not_found)
                    )
                )
            }
        }
    }

    private fun observeActivityChanges(activityId: String, walletId: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(bgDispatcher) {
            combine(activityRepo.activitiesChanged, hwWalletRepo.activities) { _, _ -> Unit }.collect {
                reloadActivity(activityId, walletId)
            }
        }
    }

    private suspend fun reloadActivity(activityId: String, walletId: String) {
        activityRepo.getActivity(activityId, walletId)
            .onSuccess { updatedActivity ->
                if (updatedActivity != null) {
                    activity = updatedActivity
                    _uiState.update {
                        it.copy(
                            activityLoadState = ActivityLoadState.Success(updatedActivity),
                            isHardwareActivity = updatedActivity.isFromHardwareWallet(),
                        )
                    }
                    loadTags()
                } else {
                    loadHwWalletActivity(activityId, walletId, observeChanges = false)
                }
            }
            .onFailure {
                Logger.warn("Failed to reload activity '$activityId'", it, context = TAG)
                // Keep showing the last known state on reload failure
            }
    }

    fun loadTags() {
        val currentActivity = activity ?: return
        val id = currentActivity.rawId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getActivityTags(id, currentActivity.walletId())
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
        val currentActivity = activity ?: return
        val id = currentActivity.rawId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.removeTagsFromActivity(id, listOf(tag), currentActivity.walletId())
                .onSuccess {
                    loadTags()
                }
                .onFailure {
                    Logger.error("Failed to remove tag $tag from activity $id", it, TAG)
                }
        }
    }

    fun addTag(tag: String) {
        val currentActivity = activity ?: return
        val id = currentActivity.rawId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.addTagsToActivity(id, listOf(tag), currentActivity.walletId())
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
        val currentActivity = activity ?: return
        val id = currentActivity.rawId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.clearContact(
                forPaymentId = id,
                syncLdkPayments = false,
                walletId = currentActivity.walletId(),
            ).onSuccess {
                reloadActivity(id, currentActivity.walletId())
            }.onFailure {
                Logger.error("Failed to detach contact for activity '$id'", it, context = TAG)
            }
        }
    }

    fun fetchTransactionDetails(txid: String) {
        if (activity == null) {
            _txDetails.update { null }
            _isTxDetailsLoading.update { false }
            _isTxDetailsUnavailable.update { true }
            return
        }
        val walletId = currentWalletId
        viewModelScope.launch(bgDispatcher) {
            _isTxDetailsLoading.update { true }
            _isTxDetailsUnavailable.update { false }
            activityRepo.getTransactionDetails(txid, walletId)
                .onSuccess { transactionDetails ->
                    _txDetails.update { transactionDetails }
                    _isTxDetailsUnavailable.update { transactionDetails == null }
                }
                .onFailure { e ->
                    Logger.error("fetchTransactionDetails error", e, context = TAG)
                    _txDetails.update { null }
                    _isTxDetailsUnavailable.update { true }
                }
            _isTxDetailsLoading.update { false }
        }
    }

    fun clearTransactionDetails() {
        _txDetails.update { null }
        _isTxDetailsLoading.update { false }
        _isTxDetailsUnavailable.update { false }
    }

    fun onClickBoost() {
        _boostSheetVisible.update { true }
    }

    fun onDismissBoostSheet() {
        _boostSheetVisible.update { false }
    }

    suspend fun getBoostTxDoesExist(boostTxIds: List<String>): ImmutableMap<String, Boolean> =
        activityRepo.getBoostTxDoesExist(boostTxIds, currentWalletId).toImmutableMap()

    suspend fun isCpfpChildTransaction(txId: String): Boolean {
        return activityRepo.isCpfpChildTransaction(txId, currentWalletId)
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

                val orderId = transferRepo.findLspOrderIdByFundingTxId(txId).getOrNull()
                if (orderId != null) {
                    orders.find { it.id == orderId }?.let { return@withContext it }
                    blocktankRepo.getOrder(orderId, refresh = false).getOrNull()?.let {
                        return@withContext it
                    }
                }
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

    @Stable
    data class ActivityDetailUiState(
        val activityLoadState: ActivityLoadState = ActivityLoadState.Initial,
        val isHardwareActivity: Boolean = false,
    )
}
