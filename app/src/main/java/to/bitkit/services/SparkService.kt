package to.bitkit.services

import breez_sdk_spark.AssetFilter
import breez_sdk_spark.BreezSdk
import breez_sdk_spark.ConnectRequest
import breez_sdk_spark.ConversionOptions
import breez_sdk_spark.ConversionType
import breez_sdk_spark.CrossChainConfig
import breez_sdk_spark.CrossChainRouteFilter
import breez_sdk_spark.CrossChainRoutePair
import breez_sdk_spark.DepositInfo
import breez_sdk_spark.EventListener
import breez_sdk_spark.FeePolicy
import breez_sdk_spark.FetchConversionLimitsRequest
import breez_sdk_spark.FetchConversionLimitsResponse
import breez_sdk_spark.GetInfoRequest
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.GetTokensMetadataRequest
import breez_sdk_spark.ImportUnilateralExitStateRequest
import breez_sdk_spark.ImportUnilateralExitStateResponse
import breez_sdk_spark.InputType
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
import breez_sdk_spark.SyncWalletRequest
import breez_sdk_spark.TokenMetadata
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
import to.bitkit.models.safe
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

        /** Leeway in sat/vB over the recommended fee that an automatic deposit claim may pay. */
        private const val DEPOSIT_CLAIM_FEE_LEEWAY_SAT_PER_VB = 2uL

        /** Share of a deposit, in parts per million, that an automatic deposit claim may pay on top. */
        private const val DEPOSIT_CLAIM_FEE_PPM = 2_000u

        private const val PPM_DIVISOR = 1_000_000uL

        /** Size of the transaction that claims a deposit, which prices the on-chain part of its fee. */
        private const val CLAIM_TX_SIZE_VBYTES = 99uL

        /**
         * The most Spark may keep to claim a deposit of [amountSats] under this wallet's fee cap, at the fastest
         * recommended fee rate [fastestFeeRate]. The provider quotes the real fee only once the deposit exists.
         */
        fun maxDepositClaimFeeSats(fastestFeeRate: ULong, amountSats: ULong): ULong {
            val rate = fastestFeeRate.safe() + DEPOSIT_CLAIM_FEE_LEEWAY_SAT_PER_VB.safe()
            val onchain = rate.safe() * CLAIM_TX_SIZE_VBYTES.safe()
            val proportional = amountSats.safe() * DEPOSIT_CLAIM_FEE_PPM.toULong().safe() / PPM_DIVISOR
            return onchain.safe() + proportional.safe()
        }

        /**
         * Returns as soon as the payment starts, like ldk-node, so its completion reaches callers as an event they
         * are already watching for; waiting for completion here fired that event before they watched.
         */
        fun bolt11SendOptions(prefersSpark: Boolean): SendPaymentOptions =
            SendPaymentOptions.Bolt11Invoice(preferSpark = prefersSpark, completionTimeoutSecs = null)
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
            if (network == SparkNetwork.MAINNET) crossChainConfig = CrossChainConfig()
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

    /** Syncs with Spark now instead of waiting for the SDK's next background sync. */
    suspend fun syncWallet() {
        requireSdk().syncWallet(SyncWalletRequest)
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
            is SendPaymentMethod.Bolt11Invoice -> bolt11SendOptions(prefersSpark)
            is SendPaymentMethod.BitcoinAddress -> SendPaymentOptions.BitcoinAddress(OnchainConfirmationSpeed.MEDIUM)
            else -> null
        }
        return requireSdk().sendPayment(SendPaymentRequest(prepared, options)).payment
    }

    /** The fastest recommended fee rate, in sat/vB, from the SDK's own fee source. */
    suspend fun fastestFeeRate(): ULong = requireSdk().recommendedFees().fastestFee

    suspend fun listUnclaimedDeposits(): List<DepositInfo> =
        requireSdk().listUnclaimedDeposits(ListUnclaimedDepositsRequest).deposits

    suspend fun listPayments(): List<Payment> = requireSdk().listPayments(ListPaymentsRequest()).payments

    suspend fun listTokenPayments(tokenId: String): List<Payment> =
        requireSdk().listPayments(ListPaymentsRequest(assetFilter = AssetFilter.Token(tokenId))).payments

    /**
     * A Spark invoice for [tokenId], so a payer scanning it knows which token to send; the wallet's plain Spark
     * address receives tokens too but doesn't name one.
     */
    suspend fun receiveTokenInvoice(tokenId: String, amount: BigInteger?): String {
        val method = ReceivePaymentMethod.SparkInvoice(
            amount = amount,
            tokenIdentifier = tokenId,
            expiryTime = null,
            description = null,
            senderPublicKey = null,
        )
        return requireSdk().receivePayment(ReceivePaymentRequest(method)).paymentRequest
    }

    suspend fun tokenMetadata(tokenId: String): TokenMetadata? =
        requireSdk().getTokensMetadata(GetTokensMetadataRequest(listOf(tokenId))).tokensMetadata.firstOrNull()

    /** The minimum amounts Flashnet converts, for [type] and the dollar token [tokenId]. */
    suspend fun conversionLimits(type: ConversionType, tokenId: String): FetchConversionLimitsResponse =
        requireSdk().fetchConversionLimits(FetchConversionLimitsRequest(type, tokenId))

    /**
     * Prepares a conversion within this wallet: a payment to its own Spark address, which the SDK runs as a
     * self-transfer through Flashnet. [amount] is in token units both ways: what to get when converting to the token,
     * what to give when converting to bitcoin.
     */
    suspend fun prepareConversion(toToken: Boolean, amount: BigInteger, tokenId: String): PrepareSendPaymentResponse {
        val sdk = requireSdk()
        val ownAddress = sdk.receivePayment(ReceivePaymentRequest(ReceivePaymentMethod.SparkAddress)).paymentRequest
        val type = if (toToken) ConversionType.FromBitcoin else ConversionType.ToBitcoin(tokenId)
        return sdk.prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(input = ownAddress),
                amount = amount,
                tokenIdentifier = tokenId,
                conversionOptions = ConversionOptions(conversionType = type),
                feePolicy = FeePolicy.FEES_INCLUDED.takeIf { !toToken },
            ),
        )
    }

    /** Routes that pay [address], an address on another chain, through a cross-chain provider. */
    suspend fun crossChainRoutes(address: String): List<CrossChainRoutePair> {
        val details = (requireSdk().parse(address) as? InputType.CrossChainAddress)?.v1
            ?: throw SparkError.NotCrossChainAddress()
        return requireSdk().getCrossChainRoutes(CrossChainRouteFilter.Send(details, null))
    }

    /** Prepares paying [amount] token units of [tokenId] to [address] on another chain through [route]. */
    suspend fun prepareCrossChain(
        address: String,
        route: CrossChainRoutePair,
        amount: BigInteger,
        tokenId: String,
    ): PrepareSendPaymentResponse = requireSdk().prepareSendPayment(
        PrepareSendPaymentRequest(
            paymentRequest = PaymentRequest.CrossChain(
                address = address,
                route = route,
                maxSlippageBps = null,
                targetOverpayBps = null
            ),
            amount = amount,
            tokenIdentifier = tokenId,
            conversionOptions = null,
            feePolicy = null,
        ),
    )

    /** The wallet's static Spark address; it receives sats and tokens alike. */
    suspend fun sparkAddress(): String =
        requireSdk().receivePayment(ReceivePaymentRequest(ReceivePaymentMethod.SparkAddress)).paymentRequest

    suspend fun parse(input: String): InputType = requireSdk().parse(input)

    /** Prepares a [tokenId] payment; [amount] in token base units, ignored by the SDK when an invoice sets one. */
    suspend fun prepareTokenSend(paymentRequest: String, amount: BigInteger?, tokenId: String) =
        requireSdk().prepareSendPayment(
            PrepareSendPaymentRequest(
                paymentRequest = PaymentRequest.Input(input = paymentRequest),
                amount = amount,
                tokenIdentifier = tokenId,
                conversionOptions = null,
                feePolicy = null,
            ),
        )

    /** Exports what a unilateral exit needs if the Spark operators stop cooperating; it can reach several MB. */
    suspend fun exportExitState(): String = requireSdk().exportUnilateralExitState().exitState

    /** Merges an exported exit state back in, without contacting the Spark operators. */
    suspend fun importExitState(exitState: String): ImportUnilateralExitStateResponse =
        requireSdk().importUnilateralExitState(ImportUnilateralExitStateRequest(exitState))

    /** The Spark network this build runs on, as exit data backups record it. */
    val networkName: String? get() = sparkNetwork()?.name?.lowercase()

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
    class NotCrossChainAddress : SparkError("Not an address on a chain the cross-chain providers serve")
}
