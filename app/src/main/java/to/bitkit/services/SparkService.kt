package to.bitkit.services

import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.EventListener
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.ListPaymentsRequest
import breez_sdk_spark.ListUnclaimedDepositsRequest
import breez_sdk_spark.MaxFee
import breez_sdk_spark.MaxOnchainFee
import breez_sdk_spark.OnchainConfirmationSpeed
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentRequest
import breez_sdk_spark.PrepareSendPaymentRequest
import breez_sdk_spark.PrepareSendPaymentResponse
import breez_sdk_spark.ReceivePaymentMethod
import breez_sdk_spark.ReceivePaymentRequest
import breez_sdk_spark.SdkEvent
import breez_sdk_spark.Seed
import breez_sdk_spark.SendPaymentMethod
import breez_sdk_spark.SendPaymentOptions
import breez_sdk_spark.SendPaymentRequest
import breez_sdk_spark.connect
import breez_sdk_spark.defaultConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lightningdevkit.ldknode.Network
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.io.File
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton
import breez_sdk_spark.Network as SparkNetwork

/**
 * Wraps the Breez SDK on Spark, which keeps Lightning available without channels after Blocktank stopped
 * selling them. The Spark wallet derives its keys from the Bitkit seed on its own path (`m/8797555'`).
 */
@Singleton
class SparkService @Inject constructor(
    private val keychain: Keychain,
) {
    companion object {
        private const val TAG = "SparkService"

        /** Seconds a Lightning send waits for completion before it returns the payment as pending. */
        private const val SEND_COMPLETION_TIMEOUT_SECS = 30u

        /** Leeway in sat/vB over the recommended fee that an automatic deposit claim may pay. */
        private const val DEPOSIT_CLAIM_FEE_LEEWAY_SAT_PER_VB = 2uL

        /** Share of a deposit, in parts per million, that an automatic deposit claim may pay on top. */
        private const val DEPOSIT_CLAIM_FEE_PPM = 2_000u
    }

    private val mutex = Mutex()
    private var sdk: BreezSdk? = null
    private var listenerId: String? = null

    private val _events = MutableSharedFlow<SdkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SdkEvent> = _events.asSharedFlow()

    /** Spark runs on mainnet with a Breez API key, and on Lightspark's hosted regtest, which needs none. */
    val isSupported: Boolean
        get() = when (Env.network) {
            Network.BITCOIN -> Env.breezApiKey != null
            Network.REGTEST -> true
            else -> false
        }

    val isConnected: Boolean get() = sdk != null

    /**
     * Spark's regtest has no Lightning network, so there a Lightning invoice from another Spark wallet is paid as a
     * Spark transfer, which lets two dev builds pay each other's invoices.
     */
    val prefersSpark: Boolean get() = Env.network == Network.REGTEST

    suspend fun connect(walletIndex: Int) = mutex.withLock {
        if (sdk != null) return@withLock
        val network = sparkNetwork() ?: throw SparkError.Unsupported()
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw ServiceError.MnemonicNotFound()
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)
        val config = defaultConfig(network).apply {
            apiKey = Env.breezApiKey
            maxDepositClaimFee = MaxFee(
                onchain = MaxOnchainFee.NetworkRecommended(DEPOSIT_CLAIM_FEE_LEEWAY_SAT_PER_VB),
                proportionalPpm = DEPOSIT_CLAIM_FEE_PPM,
            )
        }
        val storageDir = File(Env.sparkStoragePath(walletIndex)).apply { mkdirs() }.path
        val connected = connect(ConnectRequest(config, Seed.Mnemonic(mnemonic, passphrase), storageDir))
        listenerId = connected.addEventListener(
            object : EventListener {
                override suspend fun onEvent(event: SdkEvent) {
                    _events.emit(event)
                }
            },
        )
        sdk = connected
        Logger.info("Connected Spark wallet on '$network'", context = TAG)
    }

    suspend fun disconnect() = mutex.withLock {
        val current = sdk ?: return@withLock
        listenerId?.let { current.removeEventListener(it) }
        current.disconnect()
        sdk = null
        listenerId = null
        Logger.info("Disconnected Spark wallet", context = TAG)
    }

    suspend fun wipeStorage(walletIndex: Int) {
        disconnect()
        File(Env.sparkStoragePath(walletIndex)).deleteRecursively()
    }

    suspend fun getInfo(): GetInfoResponse = requireSdk().getInfo(GetInfoRequest(ensureSynced = false))

    suspend fun receiveBolt11(amountSats: ULong?, description: String, expirySecs: UInt): String {
        val method = ReceivePaymentMethod.Bolt11Invoice(description, amountSats, expirySecs, null, null)
        return requireSdk().receivePayment(ReceivePaymentRequest(method)).paymentRequest
    }

    /** Returns the static on-chain address that funds the Spark wallet, claimed automatically once confirmed. */
    suspend fun depositAddress(): String {
        val method = ReceivePaymentMethod.BitcoinAddress(newAddress = null)
        return requireSdk().receivePayment(ReceivePaymentRequest(method)).paymentRequest
    }

    suspend fun prepareSend(
        paymentRequest: String,
        amountSats: ULong?,
        feePolicy: FeePolicy? = null,
    ): PrepareSendPaymentResponse =
        requireSdk().prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(input = paymentRequest),
                amount = amountSats?.let { BigInteger.valueOf(it.toLong()) },
                tokenIdentifier = null,
                conversionOptions = null,
                feePolicy = feePolicy,
            ),
        )

    suspend fun send(prepared: PrepareSendPaymentResponse): Payment {
        val options = when (prepared.paymentMethod) {
            is SendPaymentMethod.Bolt11Invoice -> SendPaymentOptions.Bolt11Invoice(
                preferSpark = prefersSpark,
                completionTimeoutSecs = SEND_COMPLETION_TIMEOUT_SECS,
            )
            is SendPaymentMethod.BitcoinAddress -> SendPaymentOptions.BitcoinAddress(OnchainConfirmationSpeed.MEDIUM)
            else -> null
        }
        return requireSdk().sendPayment(SendPaymentRequest(prepared, options)).payment
    }

    suspend fun listUnclaimedDeposits(): List<DepositInfo> =
        requireSdk().listUnclaimedDeposits(ListUnclaimedDepositsRequest).deposits

    suspend fun listPayments(): List<Payment> = requireSdk().listPayments(ListPaymentsRequest()).payments

    /** Exports what a unilateral exit needs if the Spark operators stop cooperating; it can reach several MB. */
    suspend fun exportExitState(): String = requireSdk().exportUnilateralExitState().exitState

    private fun requireSdk(): BreezSdk = sdk ?: throw SparkError.NotConnected()

    private fun sparkNetwork(): SparkNetwork? = when (Env.network) {
        Network.BITCOIN if Env.breezApiKey != null -> SparkNetwork.MAINNET
        Network.REGTEST -> SparkNetwork.REGTEST
        else -> null
    }
}

sealed class SparkError(message: String) : AppError(message) {
    class Unsupported : SparkError("Spark is not available on this network or build")
    class NotConnected : SparkError("Spark wallet is not connected")
}
