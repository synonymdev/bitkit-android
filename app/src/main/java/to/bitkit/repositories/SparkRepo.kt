package to.bitkit.repositories

import breez_sdk_spark.FeePolicy
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SendPaymentMethod
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
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.PaymentFailureReason
import org.lightningdevkit.ldknode.PaymentId
import to.bitkit.async.appScope
import to.bitkit.data.SettingsStore
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.create
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.SparkService
import to.bitkit.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import breez_sdk_spark.PaymentType as SparkPaymentType

/**
 * Lightning without channels, through the Breez SDK on Spark. Savings to Spending funds this wallet,
 * receives fall back to it when no channel has the inbound capacity, and sends fall back to it when no
 * channel has the outbound capacity. Its payments surface as ldk-node [Event]s through [nodeEvents], so the
 * receive and send flows built on node events work for both rails.
 */
@Singleton
class SparkRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val sparkService: SparkService,
    private val activityRepo: Provider<ActivityRepo>,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SparkRepo"
        private const val MSATS_PER_SAT = 1_000uL

        /**
         * Size of Spark's deposit claim transaction; the SDK prices a standard claim as the fastest recommended fee
         * rate times this size before the provider quotes it (breez/spark-sdk `CLAIM_TX_SIZE_VBYTES`).
         */
        private const val CLAIM_TX_SIZE_VBYTES = 99uL

        /** Last step of the Spending setup progress, shown once a funding deposit is claimed. */
        private const val SETUP_STEP_DONE = 3
    }

    private val scope = appScope(bgDispatcher, TAG)

    /** Deposits this app broadcast that Spark hasn't listed yet, by txid, so they show as pending at once. */
    private val broadcastDeposits = ConcurrentHashMap<String, ULong>()
    private var eventsJob: Job? = null

    private val _state = MutableStateFlow(SparkState(isSupported = sparkService.isSupported))
    val state: StateFlow<SparkState> = _state.asStateFlow()

    private val _nodeEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val nodeEvents: SharedFlow<Event> = _nodeEvents.asSharedFlow()

    suspend fun start(walletIndex: Int): Result<Unit> = withContext(bgDispatcher) {
        if (!sparkService.isSupported) return@withContext Result.success(Unit)
        runSuspendCatching {
            if (eventsJob == null) eventsJob = scope.launch { sparkService.events.collect { onSdkEvent(it) } }
            sparkService.connect(walletIndex)
            refresh()
            syncActivities()
        }.onFailure {
            Logger.error("Failed to start Spark wallet", it, context = TAG)
        }
    }

    suspend fun stop() = withContext(bgDispatcher) {
        runSuspendCatching { sparkService.disconnect() }
            .onFailure { Logger.warn("Failed to stop Spark wallet", it, context = TAG) }
        _state.update { it.copy(isReady = false) }
    }

    suspend fun wipe(walletIndex: Int) = withContext(bgDispatcher) {
        runSuspendCatching { sparkService.wipeStorage(walletIndex) }
            .onFailure { Logger.error("Failed to wipe Spark wallet", it, context = TAG) }
        _state.update { SparkState(isSupported = sparkService.isSupported) }
    }

    fun canReceive(): Boolean = _state.value.isReady

    fun canSend(amountSats: ULong): Boolean = _state.value.isReady && _state.value.balanceSats >= amountSats

    suspend fun createInvoice(amountSats: ULong?, description: String, expirySecs: UInt): Result<String> =
        withContext(bgDispatcher) {
            runSuspendCatching { sparkService.receiveBolt11(amountSats, description, expirySecs) }
        }

    /** Returns the Lightning routing fee plus Spark's 0.25% service fee for paying [bolt11]. */
    suspend fun estimateFeeSats(bolt11: String, amountSats: ULong?): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching {
            val method = sparkService.prepareSend(bolt11, amountSats).paymentMethod
            val bolt11 = method as SendPaymentMethod.Bolt11Invoice
            bolt11.sparkTransferFeeSats?.takeIf { sparkService.prefersSpark } ?: bolt11.lightningFeeSats
        }
    }

    /** Pays [bolt11] and returns its payment hash, the id ldk-node payments use, so the send flow can match it. */
    suspend fun payInvoice(bolt11: String, amountSats: ULong?): Result<PaymentId> = withContext(bgDispatcher) {
        runSuspendCatching {
            val prepared = sparkService.prepareSend(bolt11, amountSats)
            val paymentHash = (prepared.paymentMethod as SendPaymentMethod.Bolt11Invoice).invoiceDetails.paymentHash
            val payment = sparkService.send(prepared)
            Logger.info("Sent Spark payment '${payment.id}' with status '${payment.status}'", context = TAG)
            paymentHash
        }.onFailure {
            Logger.error("Failed to pay invoice with Spark", it, context = TAG)
        }
    }

    /** Estimates what Spark keeps from a deposit to claim it, the way the SDK does before the provider quotes it. */
    suspend fun estimateDepositClaimFeeSats(): Result<ULong> = withContext(bgDispatcher) {
        runSuspendCatching { sparkService.fastestFeeRate() * CLAIM_TX_SIZE_VBYTES }
    }

    suspend fun depositAddress(): Result<String> = withContext(bgDispatcher) {
        runSuspendCatching { sparkService.depositAddress() }
    }

    /** Moves the whole Spark balance to the on-chain [address], fees included, for Spending to Savings. */
    suspend fun withdrawAll(address: String): Result<Unit> = withContext(bgDispatcher) {
        runSuspendCatching {
            val prepared = sparkService.prepareSend(address, _state.value.balanceSats, FeePolicy.FEES_INCLUDED)
            sparkService.send(prepared)
            refresh()
        }.onFailure {
            Logger.error("Failed to withdraw from Spark", it, context = TAG)
        }
    }

    /**
     * Lists a deposit the app just broadcast as pending right away, then syncs so Spark sees it sooner than its
     * minute-long background sync; Spark's own deposit state replaces it once listed.
     */
    fun onDepositBroadcast(txid: String, amountSats: ULong) {
        broadcastDeposits[txid] = amountSats
        _state.update { it.withBroadcastDeposits() }
        scope.launch {
            runSuspendCatching {
                sparkService.syncWallet()
                refresh()
            }.onFailure { Logger.warn("Failed to sync Spark after deposit '$txid'", it, context = TAG) }
        }
    }

    private fun SparkState.withBroadcastDeposits(): SparkState {
        val known = deposits.map { it.txid }.toSet()
        val local = broadcastDeposits.filterKeys { it !in known }
            .map { (txid, amount) -> SparkDeposit(txid = txid, amountSats = amount, isClaimed = false) }
        if (local.isEmpty()) return this
        val all = local + deposits
        return copy(
            deposits = all.toImmutableList(),
            pendingDepositSats = all.filterNot { it.isClaimed }.sumOf { it.amountSats },
        )
    }

    suspend fun refreshState(): Result<Unit> = withContext(bgDispatcher) {
        if (!sparkService.isConnected) return@withContext Result.success(Unit)
        runSuspendCatching { refresh() }.onFailure { Logger.warn("Failed to refresh Spark state", it, context = TAG) }
    }

    private suspend fun refresh() {
        val info = sparkService.getInfo()
        val pending = sparkService.listUnclaimedDeposits().map {
            SparkDeposit(txid = it.txid, amountSats = it.amountSats, isClaimed = false)
        }
        val claimed = sparkService.listPayments()
            .filter { it.method == PaymentMethod.DEPOSIT && it.status == PaymentStatus.COMPLETED }
            .mapNotNull { payment ->
                val deposit = payment.details as? PaymentDetails.Deposit ?: return@mapNotNull null
                SparkDeposit(
                    txid = deposit.txId,
                    amountSats = payment.amount.toLong().toULong(),
                    isClaimed = true,
                    feeSats = payment.fees.toLong().toULong(),
                    claimedAt = payment.timestamp,
                )
            }
        (pending + claimed).forEach { broadcastDeposits.remove(it.txid) }
        _state.update {
            it.copy(
                isReady = true,
                balanceSats = info.balanceSats,
                pendingDepositSats = pending.sumOf { deposit -> deposit.amountSats },
                deposits = (pending + claimed).toImmutableList(),
                recoverableFundsSats = info.recoverableFundsSats,
            ).withBroadcastDeposits()
        }
    }

    private suspend fun syncActivities() {
        sparkService.listPayments().forEach { recordActivity(it) }
    }

    private suspend fun onSdkEvent(event: SdkEvent) {
        runSuspendCatching {
            when (event) {
                is SdkEvent.Synced -> refresh()
                is SdkEvent.PaymentSucceeded -> onPayment(event.payment)
                is SdkEvent.PaymentFailed -> onPayment(event.payment)
                is SdkEvent.PaymentPending -> recordActivity(event.payment)
                is SdkEvent.UnclaimedDeposits -> Logger.warn(
                    "Found '${event.unclaimedDeposits.size}' unclaimed Spark deposits",
                    context = TAG,
                )
                is SdkEvent.NewDeposits -> refresh()
                is SdkEvent.ClaimedDeposits -> {
                    refresh()
                    settingsStore.update {
                        if (it.lightningSetupStep > 0) it.copy(lightningSetupStep = SETUP_STEP_DONE) else it
                    }
                }
                else -> Unit
            }
        }.onFailure {
            Logger.error("Failed to handle Spark event '$event'", it, context = TAG)
        }
    }

    private suspend fun onPayment(payment: Payment) {
        refresh()
        recordActivity(payment)
        payment.toNodeEvent()?.let { _nodeEvents.emit(it) }
    }

    private suspend fun recordActivity(payment: Payment) {
        val lightning = payment.details as? PaymentDetails.Lightning ?: return
        if (payment.status == PaymentStatus.PENDING && payment.paymentType == SparkPaymentType.RECEIVE) {
            return
        }
        val activity = LightningActivity.create(
            id = lightning.htlcDetails.paymentHash,
            txType = when (payment.paymentType) {
                SparkPaymentType.SEND -> PaymentType.SENT
                SparkPaymentType.RECEIVE -> PaymentType.RECEIVED
            },
            status = when (payment.status) {
                PaymentStatus.COMPLETED -> PaymentState.SUCCEEDED
                PaymentStatus.PENDING -> PaymentState.PENDING
                PaymentStatus.FAILED -> PaymentState.FAILED
            },
            value = payment.amount.toLong().toULong(),
            invoice = lightning.invoice,
            timestamp = payment.timestamp,
            fee = payment.fees.toLong().toULong(),
            message = lightning.description.orEmpty(),
            preimage = lightning.htlcDetails.preimage,
        )
        activityRepo.get().upsertActivity(Activity.Lightning(activity))
    }

    private fun Payment.toNodeEvent(): Event? {
        val lightning = details as? PaymentDetails.Lightning ?: return null
        val hash = lightning.htlcDetails.paymentHash
        val amountMsat = amount.toLong().toULong() * MSATS_PER_SAT
        return when (paymentType to status) {
            SparkPaymentType.RECEIVE to PaymentStatus.COMPLETED ->
                Event.PaymentReceived(hash, hash, amountMsat, emptyList())
            SparkPaymentType.SEND to PaymentStatus.COMPLETED ->
                Event.PaymentSuccessful(
                    hash,
                    hash,
                    lightning.htlcDetails.preimage,
                    fees.toLong().toULong() * MSATS_PER_SAT
                )
            SparkPaymentType.SEND to PaymentStatus.FAILED ->
                Event.PaymentFailed(hash, hash, PaymentFailureReason.UNEXPECTED_ERROR)
            else -> null
        }
    }
}

data class SparkState(
    val isSupported: Boolean = false,
    val isReady: Boolean = false,
    val balanceSats: ULong = 0uL,
    /** Deposits seen on-chain that Spark claims into [balanceSats] after 3 confirmations. */
    val pendingDepositSats: ULong = 0uL,
    val deposits: ImmutableList<SparkDeposit> = persistentListOf(),
    val recoverableFundsSats: ULong = 0uL,
)

/** An on-chain deposit into Spark: pending until Spark claims it after 3 confirmations. */
data class SparkDeposit(
    val txid: String,
    val amountSats: ULong,
    val isClaimed: Boolean,
    val feeSats: ULong = 0uL,
    val claimedAt: ULong? = null,
)
