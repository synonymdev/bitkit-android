package to.bitkit.ui

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ldk.enums.Currency
import org.ldk.enums.RetryableSendFailure
import org.ldk.structs.Bolt11Invoice
import org.ldk.structs.ChannelDetails
import org.ldk.structs.Logger
import org.ldk.structs.Option_u16Z
import org.ldk.structs.Option_u64Z
import org.ldk.structs.PaymentError
import org.ldk.structs.Result_Bolt11InvoiceParseOrSemanticErrorZ
import org.ldk.structs.Result_Bolt11InvoiceSignOrCreationErrorZ
import org.ldk.structs.Result_ThirtyTwoBytesPaymentErrorZ
import org.ldk.structs.Retry
import org.ldk.structs.UtilMethods
import to.bitkit._LDK
import to.bitkit.bdk.btcAddress
import to.bitkit.bdk.btcBalance
import to.bitkit.bdk.mnemonicPhrase
import to.bitkit.bdk.newAddress
import to.bitkit.data.RestApi
import to.bitkit.data.Syncer
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.toHex
import to.bitkit.ldk.Ldk
import to.bitkit.ldk.LdkLogger
import javax.inject.Inject

const val HOST = "10.0.2.2"
const val REST = "http://$HOST:3002"
const val PORT = "9736"
const val PEER = "02faf2d1f5dc153e8931d8444c4439e46a81cb7eeadba8562e7fec3690c261ce87"

@HiltViewModel
class MainViewModel @Inject constructor(
    private val syncer: Syncer,
    private val restApi: RestApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val btcAddress = mutableStateOf(btcAddress())
    val ldkNodeId = mutableStateOf(ldkNodeId())
    val ldkBalance = mutableStateOf(ldkLocalBalance())
    val btcBalance = mutableStateOf("Loading…")
    val channels = mutableStateListOf(*ldkUsableChannels())
    val peers = mutableStateListOf<String>()
    val mnemonic = mutableStateOf(mnemonicPhrase())

    init {
        sync()
    }

    fun sync() {
        btcBalance.value = "Syncing…"
        viewModelScope.launch(ioDispatcher) {
            delay(500)
            syncer.sync()
            delay(1500)
            syncPeers()
            syncChannels()
            syncBalance()
        }
    }

    private fun syncBalance() {
        btcBalance.value = btcBalance()
        ldkBalance.value = ldkLocalBalance()
    }

    fun getNewAddress() {
        btcAddress.value = newAddress()
    }

    private fun syncChannels() {
        channels.clear()
        channels.addAll(ldkUsableChannels())
    }

    fun connectPeer(pubKey: String = PEER, port: String = PORT) = with(viewModelScope) {
        launch(ioDispatcher) {
            val didConnect = restApi.connectPeer(pubKey, HOST, port.toInt())
            if (didConnect) {
                delay(250)
                syncPeers()
            }
        }
    }

    private fun disconnectPeer() = with(viewModelScope) {
        launch(ioDispatcher) {
            val didDisconnect = restApi.disconnectPeer(PEER)
            if (didDisconnect) {
                delay(250)
                syncPeers()
            }
        }
    }

    fun togglePeerConnection() {
        if (peers.contains(PEER)) {
            disconnectPeer()
        } else {
            connectPeer()
        }
        syncChannels()
    }

    private fun syncPeers() {
        peers.clear()
        peers.addAll(getPeers())
        syncChannels()
    }

    private fun getPeers(): List<String> {
        val peerManager = Ldk.channelManagerConstructor.peer_manager
        return peerManager?._peer_node_ids?.map { it._a.toHex() }.orEmpty()
    }

    fun createInvoice(
        description: String = "coffee",
        mSats: Long = 10000L,
    ): String {
        val logger: Logger = Logger.new_impl(LdkLogger)

        val invoice = UtilMethods.create_invoice_from_channelmanager(
            Ldk.channelManager,
            Ldk.keysManager.inner.as_NodeSigner(),
            logger,
            Currency.LDKCurrency_Regtest,
            Option_u64Z.some(mSats),
            description,
            300,
            Option_u16Z.some(144),
        )

        val encoded =
            (invoice as Result_Bolt11InvoiceSignOrCreationErrorZ.Result_Bolt11InvoiceSignOrCreationErrorZ_OK).res
        return encoded.to_str()
    }
}

internal fun ldkNodeId(): String {
    return Ldk.channelManager._our_node_id?.toHex() ?: throw Error("Node not initialized")
}

internal fun ldkUsableChannels(): Array<out ChannelDetails> {
    return Ldk.channelManager.list_channels().orEmpty()
}

internal fun ldkLocalBalance(): String {
    val localBalance = ldkUsableChannels().sumOf { it._balance_msat } / 1000
    Log.d(_LDK, "LN balance: $localBalance")
    return localBalance.toString()
}

internal fun payInvoice(bolt11Invoice: String): Boolean {
    Log.d(_LDK, "Paying invoice: $bolt11Invoice")

    val invoice = decodeInvoice(bolt11Invoice)

    val res = UtilMethods.pay_invoice(
        invoice,
        Retry.attempts(6),
        Ldk.channelManagerConstructor.channel_manager,
    )
    if (res.is_ok) {
        Log.d(_LDK, "Payment successful")
        return true
    }

    val error = res as? Result_ThirtyTwoBytesPaymentErrorZ.Result_ThirtyTwoBytesPaymentErrorZ_Err
    val invoiceError = error?.err as? PaymentError.Invoice
    if (invoiceError != null) {
        Log.d(_LDK, "Payment failed: $invoiceError")
        return true
    }

    when (val failure = (error?.err as? PaymentError.Sending)?.sending) {
        RetryableSendFailure.LDKRetryableSendFailure_DuplicatePayment ->
            Log.e(_LDK, "Payment failed: DuplicatePayment")

        RetryableSendFailure.LDKRetryableSendFailure_PaymentExpired ->
            Log.e(_LDK, "Payment failed: PaymentExpired")

        RetryableSendFailure.LDKRetryableSendFailure_RouteNotFound ->
            Log.e(_LDK, "Payment failed: RouteNotFound")

        else ->
            Log.e(_LDK, "Payment failed with unknown error: $failure")
    }
    return false
}

internal fun decodeInvoice(bolt11Invoice: String): Bolt11Invoice? {
    val res = Bolt11Invoice.from_str(bolt11Invoice)
    if (res is Result_Bolt11InvoiceParseOrSemanticErrorZ.Result_Bolt11InvoiceParseOrSemanticErrorZ_Err) {
        Log.d(_LDK, "Unable to parse invoice ${res.err}")
    }
    val invoice =
        (res as Result_Bolt11InvoiceParseOrSemanticErrorZ.Result_Bolt11InvoiceParseOrSemanticErrorZ_OK).res

    if (res.is_ok) {
        Log.d(_LDK, "Invoice parsed successfully")
    } else {
        Log.d(_LDK, "Unable to parse invoice")
    }
    return invoice
}