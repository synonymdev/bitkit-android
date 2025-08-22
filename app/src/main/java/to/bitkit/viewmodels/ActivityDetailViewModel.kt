package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.canBeBoosted
import to.bitkit.ext.isBoosted
import to.bitkit.ext.isFinished
import to.bitkit.ext.isSent
import to.bitkit.ext.isTransfer
import to.bitkit.ext.rawId
import to.bitkit.services.CoreService
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import to.bitkit.utils.TxDetails
import javax.inject.Inject

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val addressChecker: AddressChecker,
    private val coreService: CoreService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _txDetails = MutableStateFlow<TxDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _uiState = MutableStateFlow(ActivityDetailUiState(screenState = ActivityDetailScreenState.Loading))
    val uiState = _uiState.asStateFlow()

    private val _boostSheetVisible = MutableStateFlow(false)
    val boostSheetVisible = _boostSheetVisible.asStateFlow()

    private var activity: Activity? = null

    fun setActivity(activity: Activity) {
        this.activity = activity
        val paymentValue = when (activity) {
            is Activity.Lightning -> activity.v1.value
            is Activity.Onchain -> activity.v1.value
        }
        _uiState.update {
            it.copy( // TODO EXTRACT
                screenState = ActivityDetailScreenState.Success(
                    isLightning = activity is Activity.Lightning,
                    isSent = activity.isSent(),
                    timestamp = when (activity) {
                        is Activity.Lightning -> activity.v1.timestamp
                        is Activity.Onchain -> when (activity.v1.confirmed) {
                            true -> activity.v1.confirmTimestamp ?: activity.v1.timestamp
                            else -> activity.v1.timestamp
                        }
                    },
                    paymentValue = paymentValue,
                    fee = when (activity) {
                        is Activity.Lightning -> activity.v1.fee
                        is Activity.Onchain -> activity.v1.fee
                    },
                    isSelfSend = activity.isSent() && paymentValue == 0uL,
                    isTransfer = activity.isTransfer(),
                    paymentState = (activity as? Activity.Lightning)?.v1?.status,
                    isBoosted = activity.isBoosted(),
                    canBeBoosted = activity.canBeBoosted(),
                    isConfirmed = activity.isFinished(),
                    message = (activity as? Activity.Lightning)?.v1?.message.orEmpty(),
                    activityId = activity.rawId(),
                    doesExist = (activity as? Activity.Onchain)?.v1?.doesExist == true,
                )
            )
        }
        loadTags()
    }

    fun loadTags() {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            try {
                val activityTags = coreService.activity.tags(forActivityId = id)
                (_uiState.value.screenState as? ActivityDetailScreenState.Success)?.let { successState ->
                    _uiState.update {
                        ActivityDetailUiState(
                            successState.copy(
                                tags = activityTags
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.error("Failed to load tags for activity $id", e, TAG)
            }
        }
    }

    fun removeTag(tag: String) {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            try {
                coreService.activity.dropTags(fromActivityId = id, tags = listOf(tag))
                loadTags()
            } catch (e: Exception) {
                Logger.error("Failed to remove tag $tag from activity $id", e, TAG)
            }
        }
    }

    fun addTag(tag: String) {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            try {
                val result = coreService.activity.appendTags(toActivityId = id, tags = listOf(tag))
                if (result.isSuccess) {
                    settingsStore.addLastUsedTag(tag)
                    loadTags()
                }
            } catch (e: Exception) {
                Logger.error("Failed to add tag $tag to activity $id", e, TAG)
            }
        }
    }

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch(bgDispatcher) {
            try {
                // TODO replace with bitkit-core method when available
                _txDetails.value = addressChecker.getTransaction(txid)
            } catch (e: Throwable) {
                Logger.error("fetchTransactionDetails error", e, context = TAG)
                _txDetails.value = null
            }
        }
    }

    fun clearTransactionDetails() {
        _txDetails.value = null
    }

    fun onClickBoost() {
        _boostSheetVisible.update { true }
    }

    fun onDismissBoostSheet() {
        _boostSheetVisible.update { false }
    }

    private companion object {
        const val TAG = "ActivityDetailViewModel"
    }
}


data class ActivityDetailUiState(
    val screenState: ActivityDetailScreenState = ActivityDetailScreenState.Loading,
)

sealed interface ActivityDetailScreenState {
    data object Loading : ActivityDetailScreenState
    data class Success(
        val activityId: String?,
        val isLightning: Boolean,
        val isSent: Boolean,
        val timestamp: ULong,
        val paymentValue: ULong,
        val fee: ULong?,
        val isSelfSend: Boolean,
        val isTransfer: Boolean,
        val paymentState: PaymentState?,
        val tags: List<String> = emptyList(),
        val isBoosted: Boolean,
        val canBeBoosted: Boolean,
        val isConfirmed: Boolean,
        val message: String,
        val doesExist: Boolean,
    ) : ActivityDetailScreenState
}
