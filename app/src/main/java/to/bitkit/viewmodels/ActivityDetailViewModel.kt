package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
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

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags = _tags.asStateFlow()

    private val _boostSheetVisible = MutableStateFlow(false)
    val boostSheetVisible = _boostSheetVisible.asStateFlow()

    private var activity: Activity? = null
        set(value) {
            value?.let { activity ->
                val paymentValue =  when (activity) {
                    is Activity.Lightning -> activity.v1.value
                    is Activity.Onchain -> activity.v1.value
                }
                _uiState.update {
                    it.copy( //TODO EXTRACT
                        screenState = ActivityDetailScreenState.Success(
                            isLightning = activity is Activity.Lightning,
                            isSent = activity.isSent(),
                            timeStamp= when (activity) {
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
                            isTransfer = activity.isTransfer()
                        )
                    )
                }
            }
        }

    fun setActivity(activity: Activity) {
        this.activity = activity
        loadTags()
    }

    fun loadTags() {
        val id = activity?.rawId() ?: return
        viewModelScope.launch(bgDispatcher) {
            try {
                val activityTags = coreService.activity.tags(forActivityId = id)
                _tags.value = activityTags
            } catch (e: Exception) {
                Logger.error("Failed to load tags for activity $id", e, TAG)
                _tags.value = emptyList()
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
        val isLightning: Boolean,
        val isSent: Boolean,
        val timeStamp: ULong,
        val paymentValue: ULong,
        val fee: ULong?,
        val isSelfSend: Boolean,
        val isTransfer: Boolean,
    ) : ActivityDetailScreenState
}
