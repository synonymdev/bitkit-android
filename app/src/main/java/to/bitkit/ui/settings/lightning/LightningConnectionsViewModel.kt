package to.bitkit.ui.settings.lightning

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.ClosedChannelDetails
import com.synonym.bitkitcore.SortDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.calculateRemoteBalance
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.filterOpen
import to.bitkit.ext.filterPending
import to.bitkit.models.Toast
import to.bitkit.models.safe
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.ArkadeDeposit
import to.bitkit.repositories.ArkadeRepo
import to.bitkit.repositories.ArkadeState
import to.bitkit.repositories.ArkadeWithdrawal
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LogsRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class LightningConnectionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val blocktankRepo: BlocktankRepo,
    private val logsRepo: LogsRepo,
    private val activityRepo: ActivityRepo,
    private val arkadeRepo: ArkadeRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "LightningConnectionsViewModel"
    }

    private val _uiState = MutableStateFlow(LightningConnectionsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeState()
        observeLdkEvents()
        loadClosedChannels()
    }

    private fun loadClosedChannels() {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getClosedChannels(SortDirection.DESC)
                .onSuccess { closedChannels ->
                    val channels = lightningRepo.lightningState.value.channels
                    val openChannels = channels.filterOpen()
                    val pendingConnections = channels.filterPending()

                    _uiState.update { state ->
                        state.copy(
                            closedChannels = closedChannels.mapIndexed { index, closedChannel ->
                                closedChannel.toChannelUi(
                                    baseIndex = openChannels.size + pendingConnections.size + index,
                                    connectionText = context.getString(R.string.lightning__connection),
                                )
                            }.reversed().toImmutableList()
                        )
                    }
                }
                .onFailure {
                    Logger.error("Failed to load closed channels", it, context = TAG)
                }
        }
    }

    private fun observeState() {
        viewModelScope.launch(bgDispatcher) {
            combine(lightningRepo.lightningState, arkadeRepo.state, ::Pair).collect { (lightningState, arkadeState) ->
                val channels = lightningState.channels
                val connectionText = context.getString(R.string.lightning__connection)

                _uiState.update {
                    it.copy(
                        isNodeRunning = lightningState.nodeLifecycleState.isRunning(),
                        openChannels = channels.filterOpen().map { channel ->
                            channel.mapToUiModel(channels, connectionText)
                        }.reversed().toImmutableList(),
                        pendingConnections = channels.filterPending()
                            .map { it.mapToUiModel(channels, connectionText) }
                            .reversed().toImmutableList(),
                        localBalance = calculateLocalBalance(channels).safe() + arkadeState.balanceSats.safe(),
                        remoteBalance = channels.calculateRemoteBalance(),
                        arkade = arkadeState.toUi(lightningRepo),
                        isArkadeReceiving = arkadeState.isReady,
                    )
                }
            }
        }
    }

    private fun observeLdkEvents() {
        viewModelScope.launch {
            lightningRepo.nodeEvents.collect { event ->
                if (event is Event.ChannelPending || event is Event.ChannelReady || event is Event.ChannelClosed) {
                    Logger.debug(
                        "Received channel event '${event::class.simpleName}', triggering refresh",
                        context = TAG,
                    )
                    refreshObservedState()
                    if (event is Event.ChannelClosed) {
                        loadClosedChannels()
                    }
                }
            }
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            refreshObservedState()
            delay(500.milliseconds)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    suspend fun refreshObservedState() {
        arkadeRepo.refreshState()
        lightningRepo.sync()
        blocktankRepo.refreshOrders()
        loadClosedChannels()
    }

    private fun calculateLocalBalance(channels: List<ChannelDetails>): ULong {
        return channels
            .filterOpen()
            .sumOf { it.amountOnClose }
    }

    fun zipLogsForSharing(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            logsRepo.zipLogsForSharing()
                .onSuccess { uri -> onReady(uri) }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
        }
    }
}

internal fun getChannelName(
    channel: ChannelDetails,
    allChannels: List<ChannelDetails>,
    connectionText: String,
): String {
    val channelIndex = allChannels.indexOfFirst { channel.channelId == it.channelId }
    if (channelIndex == -1) return channel.inboundScidAlias?.toString() ?: "${channel.channelId.take(10)}…"
    return "$connectionText ${channelIndex + 1}"
}

internal fun ChannelDetails.mapToUiModel(
    allChannels: List<ChannelDetails>,
    connectionText: String,
): ChannelUi = ChannelUi(
    name = getChannelName(this, allChannels, connectionText),
    details = this,
)

internal fun ClosedChannelDetails.toChannelUi(
    baseIndex: Int,
    connectionText: String,
): ChannelUi {
    val channelDetails = createChannelDetails().copy(
        channelId = this.channelId,
        counterpartyNodeId = this.counterpartyNodeId,
        fundingTxo = OutPoint(txid = this.fundingTxoTxid, vout = this.fundingTxoIndex),
        channelValueSats = this.channelValueSats,
        outboundCapacityMsat = this.outboundCapacityMsat,
        inboundCapacityMsat = this.inboundCapacityMsat,
        unspendablePunishmentReserve = this.unspendablePunishmentReserve,
        counterpartyUnspendablePunishmentReserve = this.counterpartyUnspendablePunishmentReserve,
        isChannelReady = false,
        isUsable = false,
    )
    return ChannelUi(
        name = "$connectionText ${baseIndex + 1}",
        details = channelDetails,
        closureReason = this.channelClosureReason.takeIf { it.isNotEmpty() }
    )
}

@Stable
data class LightningConnectionsUiState(
    val isNodeRunning: Boolean = true,
    val isRefreshing: Boolean = false,
    val openChannels: ImmutableList<ChannelUi> = persistentListOf(),
    val pendingConnections: ImmutableList<ChannelUi> = persistentListOf(),
    val closedChannels: ImmutableList<ChannelUi> = persistentListOf(),
    val localBalance: ULong = 0u,
    val remoteBalance: ULong = 0u,
    val arkade: ArkadeUi? = null,
    val isArkadeReceiving: Boolean = false,
)

/** The Arkade wallet shown as a connection, present once it holds a balance or a deposit. */
data class ArkadeUi(
    val balanceSats: ULong,
    val deposits: ImmutableList<ArkadeDepositUi> = persistentListOf(),
    val withdrawals: ImmutableList<ArkadeWithdrawal> = persistentListOf(),
    val lightningAddress: String? = null,
    /** Unix seconds of the soonest output expiry. */
    val nextExpiryAt: Long? = null,
) {
    val pendingDeposits get() = deposits.filterNot { it.deposit.isClaimed }
    val claimedDeposits get() = deposits.filter { it.deposit.isClaimed }
}

data class ArkadeDepositUi(
    val deposit: ArkadeDeposit,
    /** On-chain confirmations, known for deposits this wallet sent. */
    val confirmations: UInt? = null,
)

internal suspend fun ArkadeState.toUi(lightningRepo: LightningRepo): ArkadeUi? {
    if (!isReady) return null
    // On regtest Arkade always shows, so testers reach its deposit address to fund it from the faucet.
    if (balanceSats == 0uL && deposits.isEmpty() && Env.network != Network.REGTEST) return null
    return ArkadeUi(
        balanceSats = balanceSats,
        deposits = deposits.map {
            ArkadeDepositUi(it, if (it.isClaimed) null else lightningRepo.onchainConfirmations(it.txid))
        }.toImmutableList(),
        withdrawals = withdrawals,
        lightningAddress = lightningAddress,
        nextExpiryAt = nextExpiryAt,
    )
}

data class ChannelUi(
    val name: String,
    val details: ChannelDetails,
    val closureReason: String? = null,
)
