package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.bitkit.ext.rawId
import to.bitkit.services.CoreService
import to.bitkit.utils.AddressChecker
import to.bitkit.utils.Logger
import to.bitkit.utils.TxDetails
import uniffi.bitkitcore.Activity
import javax.inject.Inject

@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    private val addressChecker: AddressChecker,
    private val coreService: CoreService,
) : ViewModel() {
    private val _txDetails = MutableStateFlow<TxDetails?>(null)
    val txDetails = _txDetails.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags = _tags.asStateFlow()

    private var activity: Activity? = null

    fun setActivity(activity: Activity) {
        this.activity = activity
        loadTags()
    }

    fun loadTags() {
        val id = activity?.rawId() ?: return
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            try {
                val result = coreService.activity.appendTags(toActivityId = id, tags = listOf(tag))
                if (result.isSuccess) {
                    loadTags()
                }
            } catch (e: Exception) {
                Logger.error("Failed to add tag $tag to activity $id", e, TAG)
            }
        }
    }

    fun fetchTransactionDetails(txid: String) {
        viewModelScope.launch {
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

    private companion object {
        const val TAG = "ActivityDetailViewModel"
    }
}
