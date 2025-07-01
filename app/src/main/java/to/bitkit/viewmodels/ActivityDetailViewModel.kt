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

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags = _tags.asStateFlow()

    private val _boostSheetVisible = MutableStateFlow(false)
    val boostSheetVisible = _boostSheetVisible.asStateFlow()

    private var activity: Activity? = null

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
    fun onConfirmBoost(feeSats: Long) {
        _boostSheetVisible.update { false }
    }

    private companion object {
        const val TAG = "ActivityDetailViewModel"
    }
}
