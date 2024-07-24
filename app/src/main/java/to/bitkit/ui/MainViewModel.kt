package to.bitkit.ui

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.ldk.enums.Currency
import org.ldk.enums.RetryableSendFailure
import org.ldk.structs.Bolt11Invoice
import org.ldk.structs.Logger
import org.ldk.structs.Option_u16Z
import org.ldk.structs.Option_u64Z
import org.ldk.structs.PaymentError
import org.ldk.structs.Result_Bolt11InvoiceParseOrSemanticErrorZ
import org.ldk.structs.Result_Bolt11InvoiceSignOrCreationErrorZ
import org.ldk.structs.Result_ThirtyTwoBytesPaymentErrorZ
import org.ldk.structs.Retry
import org.ldk.structs.UtilMethods
import org.lightningdevkit.ldknode.ChannelDetails
import to.bitkit.LnPeer
import to.bitkit.PEER
import to.bitkit.SEED
import to.bitkit._LDK
import to.bitkit.bdk.BitcoinService
import to.bitkit.di.IoDispatcher
import to.bitkit.ldk.Ldk
import to.bitkit.ldk.LdkLogger
import to.bitkit.node.LightningService
import to.bitkit.node.connectPeer
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val ldkNodeId = mutableStateOf("Loading…")
    val ldkBalance = mutableStateOf("Loading…")
    val btcAddress = mutableStateOf("Loading…")
    val btcBalance = mutableStateOf("Loading…")
    val mnemonic = mutableStateOf(SEED)

    val peers = mutableStateListOf<String>()
    val channels = mutableStateListOf<ChannelDetails>()

    private val lightningService = LightningService.shared
    private val bitcoinService = BitcoinService.shared

    init {
        sync()
    }

    fun sync() {
        viewModelScope.launch {
            ldkNodeId.value = lightningService.nodeId()
            ldkBalance.value = lightningService.balances().totalOnchainBalanceSats.toString()

            btcAddress.value = bitcoinService.address()
            btcBalance.value = bitcoinService.balance().total.toString()
            mnemonic.value = SEED

            peers.clear()
            peers += lightningService.peers().mapNotNull { it.takeIf { p -> p.isConnected }?.nodeId }

            channels.clear()
            channels += lightningService.channels()
        }
    }

    fun getNewAddress() {
        btcAddress.value = bitcoinService.newAddress()
    }

    fun connectPeer(pubKey: String = PEER.nodeId, host: String = PEER.host, port: String = PEER.port) {
        lightningService.connectPeer(LnPeer(pubKey, host, port))
        sync()
    }

    private fun disconnectPeer() {
        lightningService.node.disconnect(PEER.nodeId)
        sync()
    }

    fun togglePeerConnection() {
        if (peers.contains(PEER.nodeId)) {
            disconnectPeer()
        } else {
            connectPeer()
        }
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
