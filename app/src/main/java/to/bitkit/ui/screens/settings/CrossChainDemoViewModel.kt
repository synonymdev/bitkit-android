package to.bitkit.ui.screens.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import breez_sdk_spark.ConversionInfo
import breez_sdk_spark.CrossChainAddressDetails
import breez_sdk_spark.CrossChainAddressFamily
import breez_sdk_spark.CrossChainRouteFilter
import breez_sdk_spark.CrossChainRoutePair
import breez_sdk_spark.InputType
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SparkAsset
import dagger.hilt.android.lifecycle.HiltViewModel
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
import to.bitkit.ext.runSuspendCatching
import to.bitkit.services.SparkService
import to.bitkit.services.UsdtService
import to.bitkit.utils.Logger
import java.math.BigDecimal
import java.math.BigInteger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Demo only (spark-24): sends Spark BTC to a stablecoin address through Breez's cross-chain routes (Flashnet
 * Orchestra). Every step is logged under [TAG] so a run can be read back from the app log.
 */
@HiltViewModel
class CrossChainDemoViewModel @Inject constructor(
    private val sparkService: SparkService,
    private val usdtService: UsdtService,
) : ViewModel() {
    companion object {
        private const val TAG = "CrossChainDemo"

        /** Default amount in sats, above the route minimum of about 1,200 sats and $0.80. */
        private const val DEFAULT_AMOUNT_SATS = "1500"

        /** How often the order is read back while it is pending. */
        private val POLL_INTERVAL = 15.seconds
    }

    private val _uiState = MutableStateFlow(CrossChainDemoUiState())
    val uiState: StateFlow<CrossChainDemoUiState> = _uiState.asStateFlow()

    private var routes: List<CrossChainRoutePair> = emptyList()
    private var prepared: PrepareSendPaymentResponse? = null
    private var trackJob: Job? = null

    init {
        val address = runCatching { usdtService.paymentAddress }.getOrDefault("")
        _uiState.update { it.copy(address = address, amountSats = DEFAULT_AMOUNT_SATS) }
        loadRoutes()
    }

    fun updateAddress(value: String) = _uiState.update { it.copy(address = value.trim(), quote = null) }

    fun updateAmount(value: String) = _uiState.update { it.copy(amountSats = value.filter(Char::isDigit), quote = null) }

    fun selectRoute(index: Int) = _uiState.update { it.copy(selectedRoute = index, quote = null) }

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
                        routes = list.map { route -> route.describe() }.toImmutableList(),
                        selectedRoute = preselected,
                    )
                }
            }.onFailure { e ->
                Logger.error("Failed to list cross-chain routes", e, context = TAG)
                _uiState.update { it.copy(isBusy = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun prepare() {
        val state = _uiState.value
        val route = routes.getOrNull(state.selectedRoute) ?: return
        val amount = state.amountSats.toULongOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, quote = null) }
            Logger.info("Preparing '$amount' sats to '${state.address}' over '${route.describe()}'", context = TAG)
            runSuspendCatching { sparkService.prepareCrossChain(state.address, route, amount) }
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
        val line = buildString {
            append("payment=${payment.status}")
            payment.conversionDetails?.let { append(" conversion=${it.status}") }
            info?.let {
                append(" order=${it.orderId} orchestra=${it.status}")
                append(" estimatedOut=${it.estimatedOut} delivered=${it.deliveredAmount}")
                append(" tx=${it.externalTxHash}")
            }
        }
        if (_uiState.value.statusLog.lastOrNull() == line) return
        Logger.info("Updated cross-chain status '$line'", context = TAG)
        _uiState.update { it.copy(statusLog = (it.statusLog + line).toImmutableList()) }
    }
}

private fun CrossChainRoutePair.describe(): String {
    val btc = acceptedAssets.firstOrNull { it.asset is SparkAsset.Bitcoin }?.limits
    val limits = btc?.let {
        " min=${it.minAmount} sat ($${cents(it.minUsdCents)}) max=${it.maxAmount ?: "-"} sat ($${cents(it.maxUsdCents)})"
    } ?: " (no BTC source)"
    return "$provider $chain/$asset chainId=$chainId contract=$contractAddress$limits"
}

private fun cents(value: ULong?): String = value?.let { BigDecimal(it.toLong()).movePointLeft(2).toPlainString() } ?: "?"

private fun BigInteger.scaled(decimals: Int): String = BigDecimal(this, decimals).toPlainString()

private fun PrepareSendPaymentResponse.toQuote(route: CrossChainRoutePair): CrossChainQuote {
    val method = paymentMethod as? SendPaymentMethod.CrossChainAddress
    val decimals = route.decimals.toInt()
    val btc = route.acceptedAssets.firstOrNull { it.asset is SparkAsset.Bitcoin }?.limits
    return CrossChainQuote(
        payeeGets = method?.let { "~${it.estimatedOut.scaled(decimals)} ${route.asset}" } ?: "?",
        amountIn = method?.let { "${it.amountIn} sat" } ?: "$amount",
        fee = method?.let { "${it.feeAmount.scaled(decimals)} ${route.asset}" } ?: "?",
        serviceFee = method?.let { m ->
            m.serviceFeeAssetDecimals?.let { "${m.serviceFeeAmount.scaled(it.toInt())} ${m.serviceFeeAsset}" }
                ?: "${m.serviceFeeAmount} ${m.serviceFeeAsset ?: "sat"}"
        } ?: "?",
        transferFee = method?.let { "${it.sourceTransferFeeSats} sat" } ?: "?",
        feeMode = method?.feeMode?.name ?: "?",
        limits = btc?.let {
            "${it.minAmount}–${it.maxAmount ?: "-"} sat ($${cents(it.minUsdCents)}–$${cents(it.maxUsdCents)})"
        } ?: "?",
        expiresAt = method?.expiresAt ?: "?",
        recipient = method?.recipientAddress ?: "?",
    )
}

@Immutable
data class CrossChainQuote(
    val payeeGets: String,
    val amountIn: String,
    val fee: String,
    val serviceFee: String,
    val transferFee: String,
    val feeMode: String,
    val limits: String,
    val expiresAt: String,
    val recipient: String,
)

@Immutable
data class CrossChainDemoUiState(
    val address: String = "",
    val amountSats: String = "",
    val routes: ImmutableList<String> = persistentListOf(),
    val selectedRoute: Int = 0,
    val quote: CrossChainQuote? = null,
    val paymentId: String? = null,
    val statusLog: ImmutableList<String> = persistentListOf(),
    val isBusy: Boolean = false,
    val error: String? = null,
)
