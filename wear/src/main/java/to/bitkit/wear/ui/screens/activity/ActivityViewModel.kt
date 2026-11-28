package to.bitkit.wear.ui.screens.activity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.wear.models.WatchPayment
import to.bitkit.wear.repositories.PaymentsRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletStatus
import java.time.Instant
import javax.inject.Inject

/**
 * The newest payments from Spark's local list, read whenever the wallet becomes connected and after every payment or
 * sync. While Spark is disconnected the last list stays on screen.
 */
@HiltViewModel
class ActivityViewModel internal constructor(
    private val paymentsRepo: PaymentsRepo,
    private val walletRepo: WalletRepo,
    private val nowSecs: () -> Long,
) : ViewModel() {
    @Inject
    constructor(
        paymentsRepo: PaymentsRepo,
        walletRepo: WalletRepo,
    ) : this(paymentsRepo, walletRepo, { Instant.now().epochSecond })

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val connected = walletRepo.walletState
                .map { it.status in connectedStatuses }
                .distinctUntilChanged()
                .filter { it }
                .map { }
            merge(connected, paymentsRepo.paymentChanges).collectLatest { load() }
        }
    }

    private suspend fun load() {
        paymentsRepo.recentPayments().onSuccess { payments ->
            _uiState.update {
                it.copy(payments = payments.toImmutableList(), isLoaded = true, nowSecs = nowSecs())
            }
        }
    }
}

/** Spark's local list can be read while Spark is connected, synced or not. */
private val connectedStatuses = setOf(WalletStatus.Ready, WalletStatus.Offline)

@Immutable
data class ActivityUiState(
    val payments: ImmutableList<WatchPayment> = persistentListOf(),
    /** Whether a list was read this session; until then the loading ring shows. */
    val isLoaded: Boolean = false,
    /** When the list was read, in epoch seconds, which the relative times count from. */
    val nowSecs: Long = 0,
) {
    fun payment(id: String): WatchPayment? = payments.firstOrNull { it.id == id }
}
