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
import to.bitkit.ext.walletId
import to.bitkit.models.USat
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BlocktankRepo
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
    private val transferRepo: TransferRepo,
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

    fun loadActivity(activityId: String, walletId: String? = null) {
        viewModelScope.launch(bgDispatcher) {
            _uiState.update { it.copy(activityLoadState = ActivityLoadState.Loading) }

            activityRepo.getActivity(activityId, walletId)
                .onSuccess { activity ->
                    if (activity != null) {
                        this@ActivityDetailViewModel.activity = activity
                        _uiState.update {
                            it.copy(activityLoadState = ActivityLoadState.Success(activity))
                        }
                        loadTags()
                        observeChanges(activityId, walletId)
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
        _tags.update { persistentListOf() }
    }

    private fun observeChanges(activityId: String, walletId: String?) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(bgDispatcher) {
            activityRepo.activitiesChanged.collect {
                reload(activityId, walletId)
            }
        }
    }

    private suspend fun reload(activityId: String, walletId: String?) {
        activityRepo.getActivity(activityId, walletId)
            .onSuccess { updatedActivity ->
                if (updatedActivity != null) {
                    activity = updatedActivity
                    _uiState.update {
                        it.copy(activityLoadState = ActivityLoadState.Success(updatedActivity))
                    }
                    loadTags()
                }
            }
    }

    fun loadTags() {
        val id = activity?.rawId() ?: return
        val walletId = activity?.walletId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getActivityTags(id, walletId)
                .onSuccess { activityTags ->
                    _tags.update { activityTags.toImmutableList() }
                }
                .onFailure {
                    _tags.update { persistentListOf() }
                }
        }
    }

    fun removeTag(tag: String) {
        val id = activity?.rawId() ?: return
        val walletId = activity?.walletId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.removeTagsFromActivity(id, listOf(tag), walletId)
                .onSuccess {
                    loadTags()
                }
        }
    }

    fun addTag(tag: String) {
        val id = activity?.rawId() ?: return
        val walletId = activity?.walletId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.addTagsToActivity(id, listOf(tag), walletId)
                .onSuccess {
                    settingsStore.addLastUsedTag(tag)
                    loadTags()
                }
        }
    }

    fun detachContact() {
        val id = activity?.rawId() ?: return
        val walletId = activity?.walletId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.clearContact(
                forPaymentId = id,
                syncLdkPayments = false,
            ).onSuccess {
                reload(id, walletId)
            }
        }
    }

    fun fetchTransactionDetails(txid: String) {
        val walletId = activity?.walletId()
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getTransactionDetails(txid, walletId)
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

    suspend fun findTransferOrderAmounts(
        channelId: String?,
        txId: String?,
    ): TransferOrderAmounts? {
        val order = findOrderForTransfer(channelId, txId) ?: return null
        return TransferOrderAmounts(
            serviceFee = USat(order.feeSat) - USat(order.clientBalanceSat),
            transferAmount = order.clientBalanceSat,
        )
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

data class TransferOrderAmounts(
    val serviceFee: ULong,
    val transferAmount: ULong,
)
