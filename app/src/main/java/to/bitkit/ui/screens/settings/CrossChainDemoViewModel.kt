package to.bitkit.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import breez_sdk_spark.ConversionInfo
import breez_sdk_spark.CrossChainAddressDetails
import breez_sdk_spark.CrossChainAddressFamily
import breez_sdk_spark.CrossChainRouteFilter
import breez_sdk_spark.CrossChainRoutePair
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.InputType
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SparkAsset
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.safe
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainSendOutcome
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.repositories.SparkRepo
import to.bitkit.services.SparkService
import to.bitkit.services.UsdtService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Demo only (spark-24): pays a stablecoin address through Breez's cross-chain routes (Flashnet Orchestra), from the
 * Spark balance, or from Savings in two legs: an on-chain send to the wallet's own Spark deposit address, claimed at
 * 3 confirmations, then the Spark send. Every step is logged under [TAG] so a run can be read back from the app log.
 */
@HiltViewModel
class CrossChainDemoViewModel @Inject constructor(
    private val sparkService: SparkService,
    private val sparkRepo: SparkRepo,
    private val lightningRepo: LightningRepo,
    private val usdtService: UsdtService,
    private val httpClient: HttpClient,
) : ViewModel() {
    companion object {
        private const val TAG = "CrossChainDemo"

        /** Default Spark amount in sats, above the route minimum of 1,200 sats and $0.80. */
        private const val SPARK_AMOUNT_SATS = "1500"

        /** Default Savings amount in sats, above Orchestra's on-chain minimum of 6,188 sats. */
        private const val SAVINGS_AMOUNT_SATS = "10000"

        /** How often the order and the deposit are read back while pending. */
        private val POLL_INTERVAL = 15.seconds

        private const val ORCHESTRA_ESTIMATE_URL = "https://orchestration.flashnet.xyz/v1/orchestration/estimate"
    }

    private val _uiState = MutableStateFlow(CrossChainDemoUiState())
    val uiState: StateFlow<CrossChainDemoUiState> = _uiState.asStateFlow()

    private var routes: List<CrossChainRoutePair> = emptyList()
    private var prepared: PrepareSendPaymentResponse? = null
    private var trackJob: Job? = null
    private var depositJob: Job? = null

    init {
        val address = runCatching { usdtService.paymentAddress }.getOrDefault("")
        _uiState.update { it.copy(address = address, amountSats = SPARK_AMOUNT_SATS) }
        loadRoutes()
    }

    fun selectMode(mode: CrossChainMode) {
        if (mode == _uiState.value.mode || _uiState.value.isLocked) return
        val amount = if (mode == CrossChainMode.Savings) SAVINGS_AMOUNT_SATS else SPARK_AMOUNT_SATS
        prepared = null
        _uiState.update { it.copy(mode = mode, amountSats = amount, quote = null, savings = null, error = null) }
    }

    fun updateAddress(value: String) = _uiState.update { it.copy(address = value.trim(), quote = null) }

    fun updateAmount(value: String) = _uiState.update {
        it.copy(amountSats = value.filter(Char::isDigit), quote = null, savings = null)
    }

    fun selectRoute(index: Int) = _uiState.update { it.copy(selectedRoute = index, quote = null, savings = null) }

    fun loadRoutes() {
        val address = _uiState.value.address
        if (address.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            runSuspendCatching {
                val details = when (val input = sparkService.parse(address)) {
                    is InputType.CrossChainAddress -> input.v1
                    else -> CrossChainAddressDetails(
                        address = address,
                        addressFamily = CrossChainAddressFamily.EVM,
                        contractAddress = null,
                        chainId = null,
                        amount = null,
                    )
                }
                sparkService.crossChainRoutes(CrossChainRouteFilter.Send(details, null))
            }.onSuccess { list ->
                routes = list
                list.forEachIndexed { i, route -> Logger.info("Listed route '$i' '${route.describe()}'", context = TAG) }
                val preselected = list.indexOfFirst {
                    it.chain.contains("arbitrum", ignoreCase = true) && it.asset.uppercase() in setOf("USDT", "USDT0")
                }.coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        routes = list.map { route -> route.label() }.toImmutableList(),
                        selectedRoute = preselected,
                    )
                }
            }.onFailure { e ->
                Logger.error("Failed to list cross-chain routes", e, context = TAG)
                _uiState.update { it.copy(isBusy = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Spark mode, or the second Savings leg once the deposit is claimed: a firm quote that Send pays. */
    fun prepare() {
        val state = _uiState.value
        val route = routes.getOrNull(state.selectedRoute) ?: return
        val amount = state.amountSats.toULongOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, quote = null) }
            Logger.info("Preparing '$amount' sats to '${state.address}' over '${route.describe()}'", context = TAG)
            // From Savings, the second leg spends exactly what Spark credited, so its fees come out of the amount.
            val feePolicy = if (state.mode == CrossChainMode.Savings) FeePolicy.FEES_INCLUDED else null
            runSuspendCatching { sparkService.prepareCrossChain(state.address, route, amount, feePolicy) }
                .onSuccess { response ->
                    prepared = response
                    val quote = response.toQuote(route)
                    Logger.info("Prepared quote '$quote'", context = TAG)
                    _uiState.update { it.copy(isBusy = false, quote = quote) }
                }
                .onFailure { e ->
                    Logger.error("Failed to prepare cross-chain send", e, context = TAG)
                    _uiState.update { it.copy(isBusy = false, error = e.message ?: e.javaClass.simpleName) }
                }
        }
    }

    /** Savings mode, before funding: what each leg costs, and Orchestra's own estimate for a direct order. */
    fun estimateSavings() {
        val state = _uiState.value
        val route = routes.getOrNull(state.selectedRoute) ?: return
        val amount = state.amountSats.toULongOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, savings = null) }
            Logger.info("Estimating '$amount' sats from savings over '${route.describe()}'", context = TAG)
            val depositAddress = sparkRepo.depositAddress().getOrElse {
                Logger.error("Failed to get the Spark deposit address", it, context = TAG)
                _uiState.update { s -> s.copy(isBusy = false, error = it.message ?: it.javaClass.simpleName) }
                return@launch
            }
            val spendable = lightningRepo.getBalancesAsync().getOrNull()?.spendableOnchainBalanceSats
            val miningFee = if (spendable != null && spendable < amount) {
                Result.failure(SavingsShortError(spendable))
            } else {
                lightningRepo.calculateTotalFee(amount, depositAddress, allowFallback = false)
            }
            val claimFee = sparkRepo.estimateMatureClaimFeeSats()
            val instantCeiling = sparkRepo.estimateDepositClaimFeeSats(amount)
            val sparkSats = claimFee.getOrNull()?.let { amount.safe() - it.safe() } ?: amount
            val leg2 = runSuspendCatching { sparkService.prepareCrossChain(state.address, route, sparkSats, FeePolicy.FEES_INCLUDED) }
                .map { it.toQuote(route) }
            val direct = runSuspendCatching { directEstimate(route, amount) }
            val estimate = SavingsEstimate(
                depositAddress = depositAddress,
                miningFee = miningFee.fold(
                    { "$it sat" },
                    { (it as? SavingsShortError)?.message ?: "unavailable: ${it.message ?: it.javaClass.simpleName}" },
                ),
                miningFeeSats = miningFee.getOrNull(),
                claimFee = claimFee.fold({ "$it sat at 3 confirmations" }, { "unavailable: ${it.message}" }),
                instantClaimCeiling = instantCeiling.fold({ "up to $it sat" }, { "unavailable: ${it.message}" }),
                sparkGets = "~$sparkSats sat",
                leg2PayeeGets = leg2.fold({ it.payeeGets }, { "unavailable: ${it.message}" }),
                leg2Fees = leg2.fold({ "${it.fee} + ${it.serviceFee}" }, { "unavailable" }),
                directPayeeGets = direct.fold({ it.payeeGets }, { "unavailable: ${it.message}" }),
                directFee = direct.fold({ it.fee }, { "unavailable" }),
                directRoute = direct.fold({ it.route }, { "unavailable" }),
            )
            Logger.info("Estimated savings swap '$estimate'", context = TAG)
            _uiState.update { it.copy(isBusy = false, savings = estimate) }
        }
    }

    /** Savings leg 1: an on-chain send to the wallet's own Spark deposit address, claimed at 3 confirmations. */
    fun fund() {
        val state = _uiState.value
        val estimate = state.savings ?: return
        val amount = state.amountSats.toULongOrNull() ?: return
        val fee = estimate.miningFeeSats ?: return
        if (state.fundingTxid != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            Logger.info("Funding Spark with '$amount' sats from savings at fee '$fee'", context = TAG)
            runSuspendCatching {
                lightningRepo.sendOnChain(
                    address = estimate.depositAddress,
                    sats = amount,
                    isTransfer = true,
                    approvedMiningFeeSats = fee,
                ).getOrThrow()
            }.onSuccess { outcome ->
                if (outcome !is OnchainSendOutcome.Accepted) {
                    Logger.warn("Funding was not accepted '$outcome'", context = TAG)
                    _uiState.update { it.copy(isBusy = false, error = "Funding not accepted: $outcome") }
                    return@onSuccess
                }
                runSuspendCatching { lightningRepo.completeAcceptedTransferFollowup(orderId = null, txid = outcome.txid) }
                sparkRepo.followDepositClaim(outcome.txid, SparkClaimSpeed.Later, 0uL)
                Logger.info("Sent funding '${outcome.txid}'", context = TAG)
                _uiState.update { it.copy(isBusy = false, fundingTxid = outcome.txid) }
                addLog("leg 1: broadcast ${outcome.txid}")
                trackDeposit(outcome.txid)
            }.onFailure { e ->
                Logger.error("Failed to fund Spark from savings", e, context = TAG)
                _uiState.update { it.copy(isBusy = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun send() {
        val response = prepared ?: return
        if (_uiState.value.paymentId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            Logger.info("Sending cross-chain payment", context = TAG)
            runSuspendCatching { sparkService.send(response) }
                .onSuccess { payment ->
                    Logger.info("Sent cross-chain payment '${payment.id}'", context = TAG)
                    _uiState.update { it.copy(isBusy = false, paymentId = payment.id) }
                    record(payment)
                    track(payment.id)
                }
                .onFailure { e ->
                    Logger.error("Failed to send cross-chain payment", e, context = TAG)
                    _uiState.update { it.copy(isBusy = false, error = e.message ?: e.javaClass.simpleName) }
                }
        }
    }

    private fun trackDeposit(txid: String) {
        depositJob?.cancel()
        depositJob = viewModelScope.launch {
            launch {
                sparkService.events.collect { event ->
                    when (event) {
                        is SdkEvent.NewDeposits -> event.newDeposits.firstOrNull { it.txid == txid }?.let {
                            addLog("leg 1: Spark sees deposit of ${it.amountSats} sat")
                        }
                        is SdkEvent.ClaimedDeposits -> event.claimedDeposits.firstOrNull { it.txid == txid }?.let {
                            onClaimed(it)
                        }
                        else -> Unit
                    }
                }
            }
            while (_uiState.value.claimedSats == null) {
                delay(POLL_INTERVAL)
                runSuspendCatching { sparkService.listUnclaimedDeposits() }
                    .onSuccess { list ->
                        list.firstOrNull { it.txid == txid }?.let {
                            addLog("leg 1: unclaimed, mature=${it.isMature} claimError=${it.claimError}")
                        }
                    }
                    .onFailure { Logger.warn("Failed to list unclaimed deposits", it, context = TAG) }
            }
        }
    }

    private fun onClaimed(deposit: DepositInfo) {
        val claimed = deposit.amountSats
        Logger.info("Claimed deposit '${deposit.txid}' of '$claimed' sats", context = TAG)
        addLog("leg 1: claimed ${deposit.amountSats} sat deposit; prepare leg 2 with what Spark credited")
        val estimatedClaimFee = _uiState.value.savings?.claimFeeSats ?: 0uL
        _uiState.update {
            it.copy(claimedSats = claimed, amountSats = (claimed.safe() - estimatedClaimFee.safe()).toString())
        }
        depositJob?.cancel()
    }

    private fun track(id: String) {
        trackJob?.cancel()
        trackJob = viewModelScope.launch {
            launch {
                sparkService.events.collect { event ->
                    val payment = when (event) {
                        is SdkEvent.PaymentMetadataUpdated -> event.payment
                        is SdkEvent.PaymentSucceeded -> event.payment
                        is SdkEvent.PaymentFailed -> event.payment
                        is SdkEvent.PaymentPending -> event.payment
                        else -> null
                    }
                    if (payment?.id == id) record(payment)
                }
            }
            while (true) {
                delay(POLL_INTERVAL)
                runSuspendCatching { sparkService.getPayment(id) }
                    .onSuccess { record(it) }
                    .onFailure { Logger.warn("Failed to read payment '$id'", it, context = TAG) }
            }
        }
    }

    private fun record(payment: Payment) {
        val info = (payment.details as? PaymentDetails.Spark)?.conversionInfo as? ConversionInfo.Orchestra
        _uiState.update {
            it.copy(
                paymentStatus = payment.status.name,
                orderId = info?.orderId ?: it.orderId,
                orderStatus = info?.status?.name ?: payment.conversionDetails?.status?.name ?: it.orderStatus,
                delivered = info?.deliveredAmount?.toString() ?: it.delivered,
                deliveryTx = info?.externalTxHash ?: it.deliveryTx,
            )
        }
        val line = buildString {
            append("payment=${payment.status}")
            payment.conversionDetails?.let { append(" conversion=${it.status}") }
            info?.let {
                append(" order=${it.orderId} orchestra=${it.status}")
                append(" estimatedOut=${it.estimatedOut} delivered=${it.deliveredAmount}")
                append(" tx=${it.externalTxHash}")
            }
        }
        addLog(line)
    }

    private fun addLog(line: String) {
        if (_uiState.value.statusLog.lastOrNull() == line) return
        Logger.info("Updated cross-chain status '$line'", context = TAG)
        _uiState.update { it.copy(statusLog = (it.statusLog + line).toImmutableList()) }
    }

    private suspend fun directEstimate(route: CrossChainRoutePair, amountSats: ULong): DirectEstimate {
        val url = "$ORCHESTRA_ESTIMATE_URL?sourceChain=bitcoin&sourceAsset=BTC" +
            "&destinationChain=${route.chain}&destinationAsset=${route.asset}&amount=$amountSats" +
            "&recipientAddress=${_uiState.value.address}"
        val body = Json.parseToJsonElement(httpClient.get(url).bodyAsText()).jsonObject
        Logger.info("Fetched direct Orchestra estimate '$body'", context = TAG)
        val out = body["estimatedOut"]?.jsonPrimitive?.content?.toBigInteger()
            ?: error(body["error"]?.toString() ?: "no estimate")
        return DirectEstimate(
            payeeGets = "~${out.scaled(route.decimals.toInt())} ${route.asset}",
            fee = "$${body["totalFeeAmountUsd"]?.jsonPrimitive?.content}",
            route = body["route"]?.jsonArray?.joinToString(" → ") { it.jsonPrimitive.content }.orEmpty(),
        )
    }
}

private class SavingsShortError(spendable: ULong) :
    AppError("needs savings balance (spendable $spendable sat)")

private data class DirectEstimate(val payeeGets: String, val fee: String, val route: String)

private fun CrossChainRoutePair.btcLimits() = acceptedAssets.firstOrNull { it.asset is SparkAsset.Bitcoin }?.limits

private fun CrossChainRoutePair.label(): String = "$chain · $asset" + if (btcLimits() == null) " (no BTC source)" else ""

private fun CrossChainRoutePair.describe(): String {
    val limits = btcLimits()?.let {
        " min=${it.minAmount} sat ($${cents(it.minUsdCents)}) max=${it.maxAmount ?: "-"} sat ($${cents(it.maxUsdCents)})"
    } ?: " (no BTC source)"
    return "$provider $chain/$asset chainId=$chainId contract=$contractAddress$limits"
}

private fun cents(value: ULong?): String = value?.let { BigDecimal(it.toLong()).movePointLeft(2).toPlainString() } ?: "?"

private fun BigInteger.scaled(decimals: Int): String = BigDecimal(this, decimals).stripTrailingZeros().toPlainString()

private fun PrepareSendPaymentResponse.toQuote(route: CrossChainRoutePair): CrossChainQuote {
    val method = paymentMethod as? SendPaymentMethod.CrossChainAddress
    val decimals = route.decimals.toInt()
    return CrossChainQuote(
        route = "${route.provider.name.lowercase()} · ${route.chain} · ${route.asset}",
        youSend = method?.let { "${it.amountIn} sat" } ?: "$amount sat",
        payeeGets = method?.let { "~${it.estimatedOut.scaled(decimals)} ${route.asset}" } ?: "?",
        fee = method?.let { "${it.feeAmount.scaled(decimals)} ${route.asset}" } ?: "?",
        serviceFee = method?.let { m ->
            m.serviceFeeAssetDecimals?.let { "${m.serviceFeeAmount.scaled(it.toInt())} ${m.serviceFeeAsset}" }
                ?: "${m.serviceFeeAmount} ${m.serviceFeeAsset ?: "sat"}"
        } ?: "?",
        transferFee = method?.let { "${it.sourceTransferFeeSats} sat" } ?: "?",
        feeMode = method?.feeMode?.name?.lowercase()?.replace('_', ' ') ?: "?",
        limits = route.btcLimits()?.let {
            "${it.minAmount} sat ($${cents(it.minUsdCents)}) – ${it.maxAmount ?: "no sat max"} ($${cents(it.maxUsdCents)})"
        } ?: "?",
        expiresAtMillis = method?.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        recipient = method?.recipientAddress ?: "?",
    )
}

enum class CrossChainMode { Spark, Savings }

@Immutable
data class CrossChainQuote(
    val route: String,
    val youSend: String,
    val payeeGets: String,
    val fee: String,
    val serviceFee: String,
    val transferFee: String,
    val feeMode: String,
    val limits: String,
    val expiresAtMillis: Long?,
    val recipient: String,
)

@Immutable
data class SavingsEstimate(
    val depositAddress: String,
    val miningFee: String,
    val miningFeeSats: ULong?,
    val claimFee: String,
    val instantClaimCeiling: String,
    val sparkGets: String,
    val leg2PayeeGets: String,
    val leg2Fees: String,
    val directPayeeGets: String,
    val directFee: String,
    val directRoute: String,
) {
    val claimFeeSats: ULong? get() = claimFee.substringBefore(" sat").toULongOrNull()
}

@Immutable
data class CrossChainDemoUiState(
    val mode: CrossChainMode = CrossChainMode.Spark,
    val address: String = "",
    val amountSats: String = "",
    val routes: ImmutableList<String> = persistentListOf(),
    val selectedRoute: Int = 0,
    val quote: CrossChainQuote? = null,
    val savings: SavingsEstimate? = null,
    val fundingTxid: String? = null,
    val claimedSats: ULong? = null,
    val paymentId: String? = null,
    val paymentStatus: String? = null,
    val orderId: String? = null,
    val orderStatus: String? = null,
    val delivered: String? = null,
    val deliveryTx: String? = null,
    val statusLog: ImmutableList<String> = persistentListOf(),
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val isLocked: Boolean get() = fundingTxid != null || paymentId != null
    val isLeg2: Boolean get() = mode == CrossChainMode.Spark || claimedSats != null
}
