// @file:Suppress("unused")

package to.bitkit.utils

import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.NodeException

// TODO add cause as inner exception
open class AppError(override val message: String? = null) : Exception(message) {
    companion object {
        @Suppress("ConstPropertyName")
        private const val serialVersionUID = 1L
    }

    constructor(cause: Throwable) : this(cause.message)

    fun readResolve(): Any {
        // Return a new instance of the class, or handle it if needed
        return this
    }
}

sealed class ServiceError(message: String) : AppError(message) {
    data object NodeNotSetup : ServiceError("Node is not setup")
    data object NodeNotStarted : ServiceError("Node is not started")
    data object NodeStartTimeout : ServiceError("Node took too long to start")
    class LdkNodeSqliteAlreadyExists(path: String) : ServiceError("LDK-node SQLite file already exists at $path")
    data object LdkToLdkNodeMigration : ServiceError("LDK to LDK-node migration issue")
    data object MnemonicNotFound : ServiceError("Mnemonic not found")
    data object NodeStillRunning : ServiceError("Node is still running")
    data object InvalidNodeSigningMessage : ServiceError("Invalid node signing message")
    data object CurrencyRateUnavailable : ServiceError("Currency rate unavailable")
    data object GeoBlocked : ServiceError("Geo blocked user")
}

// region ldk
class LdkError(private val inner: LdkException) : AppError("Unknown LDK error.") {
    constructor(inner: BuildException) : this(LdkException.Build(inner))
    constructor(inner: NodeException) : this(LdkException.Node(inner))

    override val message get() = inner.message ?: super.message

    sealed interface LdkException {
        val message: String?

        class Build(exception: BuildException) : LdkException {
            override val message = when (exception) {
                is BuildException.InvalidChannelMonitor -> "Invalid channel monitor."
                is BuildException.InvalidSeedBytes -> "Invalid seed bytes."
                is BuildException.InvalidSeedFile -> "Invalid seed file."
                is BuildException.InvalidSystemTime -> "Invalid system time."
                is BuildException.InvalidListeningAddresses -> "Invalid listening addresses."
                is BuildException.ReadFailed -> "Read failed."
                is BuildException.WriteFailed -> "Write failed."
                is BuildException.StoragePathAccessFailed -> "Storage path access failed."
                is BuildException.KvStoreSetupFailed -> "KV store setup failed."
                is BuildException.WalletSetupFailed -> "Wallet setup failed."
                is BuildException.LoggerSetupFailed -> "Logger setup failed."
                is BuildException.InvalidAnnouncementAddresses -> "Invalid announcement addresses"
                is BuildException.InvalidNodeAlias -> "Invalid node alias"
                is BuildException.NetworkMismatch -> "Network mismatch"
                else -> exception.message
            }?.let { "LDK Build error: $it" }
        }

        class Node(exception: NodeException) : LdkException {
            override val message = when (exception) {
                is NodeException.AlreadyRunning -> "The node is already running."
                is NodeException.NotRunning -> "The node is not running."
                is NodeException.OnchainTxCreationFailed -> "Failed to create on-chain transaction."
                is NodeException.ConnectionFailed -> "Connection failed."
                is NodeException.InvoiceCreationFailed -> "Invoice creation failed."
                is NodeException.InvoiceRequestCreationFailed -> "Invoice request creation failed."
                is NodeException.OfferCreationFailed -> "Offer creation failed."
                is NodeException.RefundCreationFailed -> "Refund creation failed."
                is NodeException.PaymentSendingFailed -> "Payment sending failed."
                is NodeException.ProbeSendingFailed -> "Probe sending failed."
                is NodeException.ChannelCreationFailed -> "Channel creation failed."
                is NodeException.ChannelClosingFailed -> "Channel closing failed."
                is NodeException.ChannelConfigUpdateFailed -> "Channel configuration update failed."
                is NodeException.PersistenceFailed -> "Persistence failed."
                is NodeException.FeerateEstimationUpdateFailed -> "Feerate estimation update failed."
                is NodeException.FeerateEstimationUpdateTimeout -> "Feerate estimation update timeout."
                is NodeException.WalletOperationFailed -> "Wallet operation failed."
                is NodeException.WalletOperationTimeout -> "Wallet operation timeout."
                is NodeException.OnchainTxSigningFailed -> "On-chain transaction signing failed."
                is NodeException.TxSyncFailed -> "Transaction synchronization failed."
                is NodeException.TxSyncTimeout -> "Transaction synchronization timeout."
                is NodeException.GossipUpdateFailed -> "Gossip update failed."
                is NodeException.GossipUpdateTimeout -> "Gossip update timeout."
                is NodeException.LiquidityRequestFailed -> "Liquidity request failed."
                is NodeException.InvalidAddress -> "Invalid address."
                is NodeException.InvalidSocketAddress -> "Invalid socket address."
                is NodeException.InvalidPublicKey -> "Invalid public key."
                is NodeException.InvalidSecretKey -> "Invalid secret key."
                is NodeException.InvalidOfferId -> "Invalid offer ID."
                is NodeException.InvalidNodeId -> "Invalid node ID."
                is NodeException.InvalidPaymentId -> "Invalid payment ID."
                is NodeException.InvalidPaymentHash -> "Invalid payment hash."
                is NodeException.InvalidPaymentPreimage -> "Invalid payment preimage."
                is NodeException.InvalidPaymentSecret -> "Invalid payment secret."
                is NodeException.InvalidAmount -> "Invalid amount."
                is NodeException.InvalidInvoice -> "Invalid invoice."
                is NodeException.InvalidOffer -> "Invalid offer."
                is NodeException.InvalidRefund -> "Invalid refund."
                is NodeException.InvalidChannelId -> "Invalid channel ID."
                is NodeException.InvalidNetwork -> "Invalid network."
                is NodeException.DuplicatePayment -> "Duplicate payment."
                is NodeException.UnsupportedCurrency -> "Unsupported currency."
                is NodeException.InsufficientFunds -> "Insufficient funds."
                is NodeException.LiquiditySourceUnavailable -> "Liquidity source unavailable."
                is NodeException.LiquidityFeeTooHigh -> "Liquidity fee too high."
                is NodeException.UriParameterParsingFailed -> "URI parameter parsing failed."
                is NodeException.InvalidUri -> "Invalid URI."
                is NodeException.InvalidQuantity -> "Invalid quantity."
                is NodeException.InvalidNodeAlias -> "Invalid node alias."
                is NodeException.InvalidCustomTlvs -> "Invalid custom TLVs"
                is NodeException.InvalidDateTime -> "Invalid date time"
                is NodeException.InvalidFeeRate -> "Invalid fee rate"
                is NodeException.CannotRbfFundingTransaction -> "Cannot RBF funding transaction"
                is NodeException.CoinSelectionFailed -> "Coin selection failed"
                is NodeException.NoSpendableOutputs -> "No spendable outputs"
                is NodeException.TransactionAlreadyConfirmed -> "Transaction already confirmed"
                is NodeException.TransactionNotFound -> "Transaction not found"
                else -> exception.message
            }?.let { "LDK Node error: $it" }
        }
    }
}
// endregion
