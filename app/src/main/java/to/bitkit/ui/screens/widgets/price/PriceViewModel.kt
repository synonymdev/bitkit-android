package to.bitkit.ui.screens.widgets.price

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.WidgetSize
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.PricePreferences
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.WidgetSizeDraft
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class PriceViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    val pricePreferences: StateFlow<PricePreferences> = widgetsRepo.widgetsDataFlow
        .map { it.pricePreferences }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = PricePreferences()
        )

    val isPriceWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.PRICE }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    val currentPrice: StateFlow<PriceDTO?> = widgetsRepo.priceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = null
        )

    private val _customPreferences = MutableStateFlow(PricePreferences())
    val customPreferences: StateFlow<PricePreferences> = _customPreferences.asStateFlow()

    private val sizeDraft = WidgetSizeDraft(viewModelScope, WidgetType.PRICE, widgetsRepo.widgetsDataFlow)
    val draftSize: StateFlow<WidgetSize> = sizeDraft.size

    fun setSize(size: WidgetSize) = sizeDraft.set(size)

    private val _allPrices = MutableStateFlow<ImmutableList<PriceDTO>>(persistentListOf())

    private val _previewPrice: MutableStateFlow<PriceDTO?> = MutableStateFlow(null)
    val previewPrice = _previewPrice.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _priceEffect = MutableSharedFlow<PriceEffect>(extraBufferCapacity = 1)
    val priceEffect = _priceEffect.asSharedFlow()
    private fun setPriceEffect(effect: PriceEffect) = viewModelScope.launch { _priceEffect.emit(effect) }

    init {
        initializeCustomPreferences()
        collectAllPeriodPrices()
    }

    fun setPeriod(period: GraphPeriod) {
        _customPreferences.update { preferences ->
            preferences.copy(period = period)
        }
        _previewPrice.update { _allPrices.value.firstOrNull { it.widgets.firstOrNull()?.period == period } }
    }

    fun selectTradingPair(pair: TradingPair) {
        _customPreferences.update { it.copy(enabledPairs = persistentListOf(pair)) }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = PricePreferences()
        _previewPrice.update {
            _allPrices.value.firstOrNull { it.widgets.firstOrNull()?.period == _customPreferences.value.period }
        }
    }

    fun savePreferences() {
        viewModelScope.launch {
            _isLoading.update { true }
            widgetsRepo.updatePricePreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.PRICE, sizeDraft.current)
            widgetsRepo.refreshWidget(WidgetType.PRICE)
            _previewPrice.update { null }
            setPriceEffect(PriceEffect.NavigateHome)
            _isLoading.update { false }
        }
    }

    fun removeWidget(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.PRICE)
            onComplete()
        }
    }

    fun refreshOnDisplay() {
        viewModelScope.launch {
            widgetsRepo.refreshWidget(WidgetType.PRICE)
        }
    }

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            pricePreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    private fun collectAllPeriodPrices() {
        viewModelScope.launch {
            _isLoading.update { true }
            widgetsRepo.fetchAllPeriods().onSuccess { data ->
                _allPrices.update { data.toImmutableList() }
                _isLoading.update { false }
            }.onFailure {
                Logger.warn("collectAllPeriodPrices error. Trying again in 1 second", context = TAG)
                delay(1.seconds)
                collectAllPeriodPrices()
            }
        }
    }

    companion object {
        private const val TAG = "PriceViewModel"
        private const val SUBSCRIPTION_TIMEOUT = 5000L
    }
}

sealed interface PriceEffect {
    data object NavigateHome : PriceEffect
}
