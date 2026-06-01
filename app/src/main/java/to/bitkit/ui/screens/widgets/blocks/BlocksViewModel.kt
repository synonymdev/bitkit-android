package to.bitkit.ui.screens.widgets.blocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.BlocksWidgetField
import to.bitkit.models.widget.toBlockModel
import to.bitkit.models.widget.toggleField
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class BlocksViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    // MARK: - Public StateFlows

    val blocksPreferences: StateFlow<BlocksPreferences> = widgetsRepo.widgetsDataFlow
        .map { it.blocksPreferences }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = BlocksPreferences()
        )

    val isBlocksWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.BLOCK }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    val currentBlock: StateFlow<BlockModel?> = widgetsRepo.blocksFlow.map { block ->
        block?.toBlockModel()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
        initialValue = null
    )

    // MARK: - Custom Preferences (for settings UI)

    private val _customPreferences = MutableStateFlow(BlocksPreferences())
    val customPreferences: StateFlow<BlocksPreferences> = _customPreferences.asStateFlow()

    init {
        initializeCustomPreferences()
    }

    // MARK: - Public Methods

    fun toggleField(field: BlocksWidgetField) {
        _customPreferences.update { preferences ->
            preferences.toggleField(field)
        }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = BlocksPreferences()
    }

    fun savePreferences() {
        viewModelScope.launch {
            widgetsRepo.updateBlocksPreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.BLOCK)
        }
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.BLOCK)
        }
    }

    fun refreshOnDisplay() {
        viewModelScope.launch {
            widgetsRepo.refreshWidget(WidgetType.BLOCK)
        }
    }

    // MARK: - Private Methods

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            blocksPreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}
