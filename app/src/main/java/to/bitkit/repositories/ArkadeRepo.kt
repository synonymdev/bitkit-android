package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.async.appScope
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.create
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.ArkadeExitBackupV1
import to.bitkit.services.ArkadeError
import to.bitkit.services.ArkadeEvent
import to.bitkit.services.ArkadeHistoryEntry
import to.bitkit.services.ArkadeService
import to.bitkit.services.ArkadeSwap
import to.bitkit.utils.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Lightning without channels, through Arkade (Ark Labs) and Arkade Intents. Savings to Spending boards on-chain
 * funds into the Arkade wallet, receives fall back to it when no channel has the inbound capacity, and sends fall
 * back to it when no channel has the outbound capacity. Its payments surface as ldk-node [Event]s through
 * [nodeEvents], so the receive and send flows built on node events work for both rails.
 */
@Singleton
class ArkadeRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val arkadeService: ArkadeService,
    private val activityRepo: Provider<ActivityRepo>,
) {
    companion object {
        private const val TAG = "ArkadeRepo"
        private const val MSATS_PER_SAT = 1_000uL
        private const val OUTCOME_CLAIMED = "claimed"
        private const val OUTCOME_PAID = "paid"
        private val FAILED_SEND_OUTCOMES = setOf("refunded", "cancelled", "failed")
        private const val HISTORY_RECEIVED = "RECEIVED"
        private const val HISTORY_SENT = "SENT"

        /** Prefix of the activity id for a payment Arkade received outside a swap, such as an offline LNURL receive. */
        private const val ARK_RECEIVE_ID_PREFIX = "ark:"
    }

    private val scope = appScope(bgDispatcher, TAG)

    /** Deposits this app broadcast that the Arkade wallet hasn't listed yet, by txid, so they show pending at once. */
    private val broadcastDeposits = ConcurrentHashMap<String, ULong>()

    private var eventsJob: Job? = null

    private val _state = MutableStateFlow(ArkadeState(isSupported = arkadeService.isSupported))
    val state: StateFlow<ArkadeState> = _state.asStateFlow()

    private val _nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val nodeEvents: SharedFlow<Event> = _nodeEvents.asSharedFlow()

    private val _exitStateChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once Arkade connects and whenever its outputs or swaps change, so the state backup is redone. */
    val exitStateChanged: SharedFlow<Unit> = _exitStateChanged.asSharedFlow()

    suspend fun start(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        if (!arkadeService.isSupported) return@withContext Result.success(Unit)
        runSuspendCatching {
            if (eventsJob == null) eventsJob = scope.launch { arkadeService.events.collect { onEvent(it) } }
            val restoreFile = File(Env.arkadeExitRestorePath(walletIndex))
            val backup = restoreFile.takeIf { it.exists() }?.readText()
            val startedMarker = File(Env.arkadeStartedMarkerPath(walletIndex))
            arkadeService.connect(restore = !startedMarker.exists(), backup = backup)
            startedMarker.apply { parentFile?.mkdirs() }.writeText(nowMillis().toString())
            if (backup != null) {
                restoreFile.delete()
                Logger.info("Imported Arkade wallet data from backup", context = TAG)
            }
            _exitStateChanged.emit(Unit)
            refresh()
            syncActivities()
            lightningAddress().onFailure {
                Logger.warn("Failed to get the Arkade Lightning address", it, context = TAG)
            }
            Unit
        }.onFailure {
            Logger.error("Failed to start Arkade wallet", it, context = TAG)
        }
    }

    suspend fun stop() = withContext(bgDispatcher) {
        runSuspendCatching { arkadeService.disconnect() }
            .onFailure { Logger.warn("Failed to stop Arkade wallet", it, context = TAG) }
        _state.update { it.copy(isReady = false) }
    }

    suspend fun wipe(walletIndex: Int) = withContext(bgDispatcher) {
        runSuspendCatching {
            arkadeService.wipeStorage()
            File(Env.arkadeExitRestorePath(walletIndex)).delete()
            File(Env.arkadeStartedMarkerPath(walletIndex)).delete()
        }.onFailure { Logger.error("Failed to wipe Arkade wallet", it, context = TAG) }
        _state.update { ArkadeState(isSupported = arkadeService.isSupported) }
    }

    fun canReceive(): Boolean = _state.value.isReady

    fun canSend(amountSats: ULong): Boolean = _state.value.isReady && _state.value.balanceSats >= amountSats

    /**
     * An invoice for exactly [amountSats] through Arkade's Lightning solver. Without an amount there is no invoice:
     * the wallet's Lightning address takes amountless payments instead.
     */
    suspend fun createInvoice(amountSats: ULong?, description: String, expirySecs: UInt): Result<String> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                val amount = amountSats?.takeIf { it > 0uL } ?: return@runSuspendCatching lightningAddressLnurl()
                Logger.debug("Creating Arkade invoice, '$description', expiry '$expirySecs' s", context = TAG)
                val swap = arkadeService.receive(amount)
                requireNotNull(swap.bolt11) { "Arkade returned no invoice" }
            }
        }

    private suspend fun lightningAddressLnurl(): String {
        val address = requireNotNull(arkadeService.lightningAddress()) { "Arkade has no Lightning address" }
        _state.update { it.copy(lightningAddress = address.lightningAddress) }
        return address.lnurl
    }

    /** The wallet's Lightning address, claimed on Ark Labs' LNURL server on first use; it takes payments offline. */
    suspend fun lightningAddress(): Result<String?> = withContext(bgDispatcher) {
        runSuspendCatching {
            arkadeService.lightningAddress()?.lightningAddress.also { address ->
                _state.update { it.copy(lightningAddress = address) }
            }
        }
    }

    /** Returns the solver's fee for paying [bolt11]: the difference between the lockup and the invoice amount. */
    suspend fun estimateFeeSats(bolt11: String, amountSats: ULong?): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (amountSats != null && invoiceAmountSats(bolt11) == null) throw ArkadeError.Bridge("AmountlessInvoice")
            arkadeService.quotePay(bolt11).feeSat.toULong()
        }
    }

    private fun invoiceAmountSats(bolt11: String): ULong? =
        runCatching { Bolt11Invoice.fromStr(bolt11).amountMilliSatoshis() }.getOrNull()?.let { it / MSATS_PER_SAT }

    /** Pays [bolt11] and returns its payment hash, the id ldk-node payments use, so the send flow can match it. */
    suspend fun payInvoice(bolt11: String, amountSats: ULong?): Result<PaymentId> = withContext(bgDispatcher) {
        runSuspendCatching {
            if (invoiceAmountSats(bolt11) == null) {
                throw ArkadeError.Bridge("Arkade can't pay an invoice without an amount, '$amountSats' sats asked")
            }
            val paymentHash = Bolt11Invoice.fromStr(bolt11).paymentHash()
            val swap = arkadeService.pay(bolt11, maxFeeSats = null)
            Logger.info("Funded Arkade payment '${swap.id}' with outcome '${swap.outcome}'", context = TAG)
            recordSwap(swap)
            refresh()
            paymentHash
        }.onFailure {
            Logger.error("Failed to pay invoice with Arkade", it, context = TAG)
        }
    }

    /** What the operator charges to board a deposit: its fee formula has no on-chain input term, so 0 today. */
    suspend fun boardingFeeSats(): Result<ULong> = Result.success(0uL)

    suspend fun depositAddress(): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching { arkadeService.info().boardingAddress }
    }

    /** Moves the whole Arkade balance to the on-chain [address] in the next batch, for Spending to Savings. */
    suspend fun withdrawAll(address: String): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            val txid = arkadeService.offboard(address)
            Logger.info("Offboarded Arkade balance in '$txid'", context = TAG)
            refresh()
        }.onFailure {
            Logger.error("Failed to offboard from Arkade", it, context = TAG)
        }
    }

    /** Renews every output now; the wallet does it by itself 3 days before expiry, and the delegate while closed. */
    suspend fun renew(): Result<String?> = withContext(bgDispatcher) {
        runSuspendCatching { arkadeService.renew().also { refresh() } }
            .onFailure { Logger.error("Failed to renew Arkade outputs", it, context = TAG) }
    }

    /** Lists a deposit the app just broadcast as pending right away, then boards it once it confirms. */
    fun onDepositBroadcast(txid: String, amountSats: ULong) {
        broadcastDeposits[txid] = amountSats
        _state.update { it.withBroadcastDeposits() }
        scope.launch { refreshState() }
    }

    private fun ArkadeState.withBroadcastDeposits(): ArkadeState {
        val known = deposits.map { it.txid }.toSet()
        val local = broadcastDeposits.filterKeys { it !in known }
            .map { (txid, amount) -> ArkadeDeposit(txid = txid, amountSats = amount, isClaimed = false) }
        if (local.isEmpty()) return this
        val all = local + deposits
        return copy(
            deposits = all.toImmutableList(),
            pendingDepositSats = all.filterNot { it.isClaimed }.sumOf { it.amountSats },
        )
    }

    /** The wallet data backup, or null while Arkade is not connected and there is nothing to back up. */
    suspend fun exitBackup(): Result<ArkadeExitBackupV1?> = withContext(bgDispatcher) {
        runSuspendCatching {
            val network = arkadeService.networkName
            if (!arkadeService.isConnected || network == null) return@runSuspendCatching null
            ArkadeExitBackupV1(createdAt = nowMillis(), network = network, exitState = arkadeService.exportState())
        }
    }

    /**
     * Puts restored wallet data back before the Arkade wallet next opens its databases, and has it scan the operator
     * again on that start for outputs and swaps the backup missed.
     */
    suspend fun restoreExitBackup(backup: ArkadeExitBackupV1, walletIndex: Int): Result<Unit> =
        withContext(bgDispatcher) {
            runSuspendCatching {
                if (backup.network != arkadeService.networkName) {
                    Logger.warn("Skipped Arkade wallet data from network '${backup.network}'", context = TAG)
                    return@runSuspendCatching
                }
                File(Env.arkadeExitRestorePath(walletIndex)).apply { parentFile?.mkdirs() }.writeText(backup.exitState)
                File(Env.arkadeStartedMarkerPath(walletIndex)).delete()
                if (arkadeService.isConnected) {
                    arkadeService.disconnect()
                    start(walletIndex)
                }
            }
        }

    suspend fun refreshState(): Result<Unit> = withContext(bgDispatcher) {
        if (!arkadeService.isConnected) return@withContext Result.success(Unit)
        runSuspendCatching { refresh() }.onFailure { Logger.warn("Failed to refresh Arkade state", it, context = TAG) }
    }

    private suspend fun refresh() {
        val balance = arkadeService.balance()
        val history = arkadeService.history()
        val vtxos = arkadeService.vtxos()
        val boardings = history.filter { it.boardingTxid != null && it.type == HISTORY_RECEIVED }
        val deposits = boardings.map {
            ArkadeDeposit(
                txid = checkNotNull(it.boardingTxid),
                amountSats = it.amount.toULong(),
                isClaimed = it.settled
            )
        }
        deposits.filter { it.isClaimed }.forEach { broadcastDeposits.remove(it.txid) }
        val nextExpiry = vtxos.filter { it.isSwept != true }.mapNotNull { it.batchExpiry }.minOrNull()
        _state.update {
            it.copy(
                isReady = true,
                balanceSats = balance.available.toULong(),
                pendingDepositSats = (balance.boardingConfirmed + balance.boardingUnconfirmed).toULong(),
                deposits = deposits.toImmutableList(),
                withdrawals = history.toWithdrawals(),
                recoverableFundsSats = balance.recoverable.toULong(),
                vtxoCount = vtxos.count { v -> v.isSwept != true },
                nextExpiryAt = nextExpiry?.let { e -> if (e > 1_000_000_000_000L) e / 1000 else e },
            ).withBroadcastDeposits()
        }
        _exitStateChanged.emit(Unit)
    }

    private fun List<ArkadeHistoryEntry>.toWithdrawals() =
        filter { it.type == HISTORY_SENT && it.commitmentTxid != null && it.arkTxid == null }
            .map {
                ArkadeWithdrawal(
                    txid = checkNotNull(it.commitmentTxid),
                    amountSats = it.amount.toULong(),
                    feeSats = 0uL,
                )
            }
            .toImmutableList()

    /**
     * The completion event of an Arkade Lightning send that has already settled, in case it finished before the
     * caller started watching for it.
     */
    suspend fun settledSendEvent(paymentHash: String): Event? = withContext(bgDispatcher) {
        if (!arkadeService.isConnected) return@withContext null
        listSwaps()
            .firstOrNull { it.isSend && it.paymentHash.equals(paymentHash, ignoreCase = true) }
            ?.toNodeEvent()
    }

    /** Payment hashes of the Lightning payments Arkade received. */
    suspend fun receivedLightningPaymentHashes(): Set<String> =
        lightningPaymentHashes { it.isReceive && it.outcome == OUTCOME_CLAIMED }

    /** Payment hashes of the Lightning payments Arkade sent or is sending. */
    suspend fun attemptedLightningPaymentHashes(): Set<String> =
        lightningPaymentHashes { it.isSend && it.outcome !in FAILED_SEND_OUTCOMES }

    private suspend fun lightningPaymentHashes(filter: (ArkadeSwap) -> Boolean): Set<String> =
        withContext(bgDispatcher) {
            if (!arkadeService.isConnected) return@withContext emptySet()
            listSwaps().filter(filter).mapNotNull { it.paymentHash?.lowercase() }.toSet()
        }

    private suspend fun listSwaps(): List<ArkadeSwap> = runSuspendCatching { arkadeService.swaps() }
        .onFailure { Logger.warn("Failed to list Arkade swaps", it, context = TAG) }
        .getOrDefault(emptyList())

    private suspend fun syncActivities() {
        listSwaps().forEach { recordSwap(it) }
    }

    private suspend fun onEvent(event: ArkadeEvent) {
        runSuspendCatching {
            when (event) {
                is ArkadeEvent.Swap -> onSwap(event.swap)
                is ArkadeEvent.History -> onHistory(event.entries)
                ArkadeEvent.Restarted -> {
                    Logger.warn("Restarting Arkade wallet after its WebView renderer stopped", context = TAG)
                    _state.update { it.copy(isReady = false) }
                    arkadeService.reconnect()
                    refresh()
                }
            }
        }.onFailure {
            Logger.error("Failed to handle Arkade event '$event'", it, context = TAG)
        }
    }

    private suspend fun onSwap(swap: ArkadeSwap) {
        Logger.info("Arkade swap '${swap.id}' is '${swap.outcome}'", context = TAG)
        recordSwap(swap)
        refresh()
        swap.toNodeEvent()?.let { _nodeEvents.emit(it) }
    }

    // A payment that reached the wallet outside this client's swaps: an offline receive the LNURL server claimed,
    // or a payment straight to the Arkade address. Boarding and swap claims show up through their own records.
    private suspend fun onHistory(entries: List<ArkadeHistoryEntry>) {
        refresh()
        val swapClaims = listSwaps().filter { it.isReceive }.mapNotNull { it.takeSat }.toSet()
        entries.filter { it.type == HISTORY_RECEIVED && it.boardingTxid == null && it.arkTxid != null }
            .filterNot { it.amount in swapClaims }
            .forEach { entry ->
                val id = ARK_RECEIVE_ID_PREFIX + checkNotNull(entry.arkTxid)
                if (activityRepo.get().getActivity(id).getOrNull() != null) return@forEach
                val activity = LightningActivity.create(
                    id = id,
                    txType = PaymentType.RECEIVED,
                    status = PaymentState.SUCCEEDED,
                    value = entry.amount.toULong(),
                    invoice = "",
                    timestamp = (entry.createdAt / 1000).toULong(),
                    fee = 0uL,
                    message = "",
                )
                activityRepo.get().upsertActivity(Activity.Lightning(activity))
                Logger.info("Recorded Arkade receive '$id' of '${entry.amount}' sats", context = TAG)
                _nodeEvents.emit(Event.PaymentReceived(id, id, entry.amount.toULong() * MSATS_PER_SAT, emptyList()))
            }
    }

    private suspend fun recordSwap(swap: ArkadeSwap) {
        val id = swap.paymentHash ?: return
        if (swap.isReceive && swap.outcome != OUTCOME_CLAIMED) return
        val existing = (activityRepo.get().getActivity(id).getOrNull() as? Activity.Lightning)?.v1
        val status = when (swap.outcome) {
            OUTCOME_CLAIMED, OUTCOME_PAID -> PaymentState.SUCCEEDED
            in FAILED_SEND_OUTCOMES -> PaymentState.FAILED
            else -> PaymentState.PENDING
        }
        val activity = LightningActivity.create(
            id = id,
            txType = if (swap.isSend) PaymentType.SENT else PaymentType.RECEIVED,
            status = status,
            value = (swap.takeSat ?: 0L).toULong(),
            invoice = swap.bolt11.orEmpty(),
            timestamp = (swap.updatedAt ?: swap.createdAt ?: (nowMillis() / 1000)).toULong(),
            fee = (swap.feeSat ?: 0L).toULong(),
            message = "",
            contact = existing?.contact,
            seenAt = existing?.seenAt,
        )
        activityRepo.get().upsertActivity(Activity.Lightning(activity))
    }

    private fun ArkadeSwap.toNodeEvent(): Event? {
        val hash = paymentHash ?: return null
        return when {
            isReceive && outcome == OUTCOME_CLAIMED ->
                Event.PaymentReceived(hash, hash, (takeSat ?: 0L).toULong() * MSATS_PER_SAT, emptyList())
            isSend && outcome == OUTCOME_PAID ->
                Event.PaymentSuccessful(hash, hash, null, (feeSat ?: 0L).toULong() * MSATS_PER_SAT)
            isSend && outcome in FAILED_SEND_OUTCOMES ->
                Event.PaymentFailed(hash, hash, PaymentFailureReason.UNEXPECTED_ERROR)
            else -> null
        }
    }
}

data class ArkadeState(
    val isSupported: Boolean = false,
    val isReady: Boolean = false,
    /** Spendable now: settled plus preconfirmed outputs. */
    val balanceSats: ULong = 0uL,
    /** On-chain deposits waiting to confirm or to be boarded into the next batch. */
    val pendingDepositSats: ULong = 0uL,
    val deposits: ImmutableList<ArkadeDeposit> = persistentListOf(),
    val withdrawals: ImmutableList<ArkadeWithdrawal> = persistentListOf(),
    /** Expired or swept outputs that the next renewal settles back into the balance. */
    val recoverableFundsSats: ULong = 0uL,
    val vtxoCount: Int = 0,
    /** Unix seconds of the soonest batch expiry; the wallet renews 3 days before it. */
    val nextExpiryAt: Long? = null,
    val lightningAddress: String? = null,
)

/** A Transfer to Savings out of Arkade, offboarded in a batch. */
data class ArkadeWithdrawal(
    val txid: String,
    val amountSats: ULong,
    val feeSats: ULong,
)

/** An on-chain deposit into Arkade: pending until it confirms and the wallet boards it into a batch. */
data class ArkadeDeposit(
    val txid: String,
    val amountSats: ULong,
    val isClaimed: Boolean,
    val feeSats: ULong = 0uL,
    val claimedAt: ULong? = null,
)
