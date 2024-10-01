// @file:Suppress("unused")

package to.bitkit.shared

import org.bitcoindevkit.AddressException
import org.bitcoindevkit.Bip32Exception
import org.bitcoindevkit.Bip39Exception
import org.bitcoindevkit.CalculateFeeException
import org.bitcoindevkit.CannotConnectException
import org.bitcoindevkit.CreateTxException
import org.bitcoindevkit.DescriptorException
import org.bitcoindevkit.DescriptorKeyException
import org.bitcoindevkit.ElectrumException
import org.bitcoindevkit.EsploraException
import org.bitcoindevkit.ExtractTxException
import org.bitcoindevkit.FeeRateException
import org.bitcoindevkit.ParseAmountException
import org.bitcoindevkit.PersistenceException
import org.bitcoindevkit.PsbtParseException
import org.bitcoindevkit.SignerException
import org.bitcoindevkit.TransactionException
import org.bitcoindevkit.TxidParseException
import org.bitcoindevkit.WalletCreationException
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.NodeException

// TODO add cause as inner exception
open class AppError(override val message: String? = null) : Exception(message) {
    companion object {
        @Suppress("ConstPropertyName")
        private const val serialVersionUID = 1L
    }

    fun readResolve(): Any {
        // Return a new instance of the class, or handle it if needed
        return this
    }
}

sealed class ServiceError(message: String) : AppError(message) {
    data object NodeNotSetup : ServiceError("Node is not setup")
    data object NodeNotStarted : ServiceError("Node is not started")
    data object OnchainWalletNotInitialized : ServiceError("Onchain wallet not created")
    class LdkNodeSqliteAlreadyExists(path: String) : ServiceError("LDK-node SQLite file already exists at $path")
    data object LdkToLdkNodeMigration : ServiceError("LDK to LDK-node migration issue")
    class MnemonicNotFound : ServiceError("Mnemonic not found")
    data object NodeStillRunning : ServiceError("Node is still running")
    data object OnchainWalletStillRunning : ServiceError("Onchain wallet is still running")
    data object InvalidNodeSigningMessage : ServiceError("Invalid node signing message")
}

sealed class KeychainError(message: String) : AppError(message) {
    class FailedToDelete(key: String) : KeychainError("Failed to delete $key from keychain.")
    class FailedToLoad(key: String) : KeychainError("Failed to load $key from keychain.")
    class FailedToSave(key: String) : KeychainError("Failed to save to $key keychain.")
    class FailedToSaveAlreadyExists(key: String) :
        KeychainError("Key $key already exists in keychain. Explicitly delete key before attempting to update value.")

    class KeychainWipeNotAllowed : KeychainError("Wiping keychain is only allowed in debug mode for regtest")
}

sealed class BlocktankError(message: String) : AppError(message) {
    class InvalidResponse(status: Int) : BlocktankError("Invalid response status code $status.")
    class InvalidJson : BlocktankError("Invalid JSON.")
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
                is NodeException.MessageSigningFailed -> "Message signing failed."
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
                else -> exception.message
            }?.let { "LDK Node error: $it" }
        }
    }
}
// endregion

// region bdk
class BdkError(private val inner: BdkException) : AppError("Unknown BDK error.") {
    constructor(inner: AddressException) : this(BdkException.Address(inner))
    constructor(inner: Bip32Exception) : this(BdkException.Bip32(inner))
    constructor(inner: Bip39Exception) : this(BdkException.Bip39(inner))
    constructor(inner: CalculateFeeException) : this(BdkException.CalculateFee(inner))
    constructor(inner: CannotConnectException) : this(BdkException.CannotConnect(inner))
    constructor(inner: CreateTxException) : this(BdkException.CreateTx(inner))
    constructor(inner: DescriptorException) : this(BdkException.Descriptor(inner))
    constructor(inner: DescriptorKeyException) : this(BdkException.DescriptorKey(inner))
    constructor(inner: ElectrumException) : this(BdkException.Electrum(inner))
    constructor(inner: EsploraException) : this(BdkException.Esplora(inner))
    constructor(inner: ExtractTxException) : this(BdkException.ExtractTx(inner))
    constructor(inner: FeeRateException) : this(BdkException.FeeRate(inner))
    constructor(inner: ParseAmountException) : this(BdkException.ParseAmount(inner))
    constructor(inner: PersistenceException) : this(BdkException.Persistence(inner))
    constructor(inner: PsbtParseException) : this(BdkException.PsbtParse(inner))
    constructor(inner: SignerException) : this(BdkException.Signer(inner))
    constructor(inner: TransactionException) : this(BdkException.Transaction(inner))
    constructor(inner: TxidParseException) : this(BdkException.TxidParse(inner))
    constructor(inner: WalletCreationException) : this(BdkException.WalletCreation(inner))

    override val message get() = inner.message ?: super.message

    sealed interface BdkException {
        val message: String?

        class Address(exception: AddressException) : BdkException {
            override val message = when (exception) {
                is AddressException.OtherAddressErr -> "An unspecified address error occurred."
                is AddressException.Base58 -> "Base58 encoding issue in the address."
                is AddressException.Bech32 -> "Bech32 encoding issue in the address."
                is AddressException.WitnessProgram -> "Witness program in address is invalid or corrupted."
                is AddressException.ExcessiveScriptSize -> "The script size in the address is too large."
                is AddressException.NetworkValidation -> "Network validation failed for the address."
                is AddressException.UncompressedPubkey -> "Address contains an uncompressed public key."
                is AddressException.UnrecognizedScript -> "Address script is not recognized or invalid."
                is AddressException.WitnessVersion -> "Witness version in address is incorrect."
                else -> exception.message
            }?.let { "BDK Address error: $it" }
        }

        class Bip32(exception: Bip32Exception) : BdkException {
            override val message = when (exception) {
                is Bip32Exception.Base58 -> "Base58 encoding issue in BIP32 key."
                is Bip32Exception.InvalidChildNumber -> "Invalid child number in BIP32 key derivation."
                is Bip32Exception.UnknownException -> "An unknown BIP32 error occurred."
                is Bip32Exception.Hex -> "Hexadecimal encoding error in BIP32 key."
                is Bip32Exception.CannotDeriveFromHardenedKey -> "Unable to derive key from a hardened key."
                is Bip32Exception.InvalidDerivationPathFormat -> "Invalid format in BIP32 derivation path."
                is Bip32Exception.InvalidPublicKeyHexLength -> "Public key hex length is invalid."
                is Bip32Exception.Secp256k1 -> "SECP256k1 curve error in BIP32 key."
                is Bip32Exception.UnknownVersion -> "Unknown BIP32 version."
                is Bip32Exception.WrongExtendedKeyLength -> "Extended key length is incorrect."
                is Bip32Exception.InvalidChildNumberFormat -> "Child number format in BIP32 key is invalid."
                else -> exception.message
            }?.let { "BIP32 error: $it" }
        }

        class Bip39(exception: Bip39Exception) : BdkException {
            override val message = when (exception) {
                is Bip39Exception.AmbiguousLanguages -> "The specified language for BIP39 is ambiguous."
                is Bip39Exception.BadEntropyBitCount -> "The entropy bit count in BIP39 is incorrect."
                is Bip39Exception.BadWordCount -> "Word count in BIP39 mnemonic is incorrect."
                is Bip39Exception.InvalidChecksum -> "Checksum validation failed in BIP39 mnemonic."
                is Bip39Exception.UnknownWord -> "Unknown word found in BIP39 mnemonic."
                else -> exception.message
            }?.let { "BDK BIP39 error: $it" }
        }

        class CalculateFee(exception: CalculateFeeException) : BdkException {
            override val message = when (exception) {
                is CalculateFeeException.MissingTxOut -> "Transaction output is missing during fee calculation."
                is CalculateFeeException.NegativeFee -> "Calculated fee is negative."
                else -> exception.message
            }?.let { "BDK Calculate fee error: $it" }
        }

        class CannotConnect(exception: CannotConnectException) : BdkException {
            override val message = when (exception) {
                is CannotConnectException.Include -> "Include-specific connection issue occurred."
                else -> exception.message
            }?.let { "BDK Cannot connect error: $it" }
        }

        class CreateTx(exception: CreateTxException) : BdkException {
            override val message = when (exception) {
                is CreateTxException.ChangePolicyDescriptor -> "Error with change policy descriptor."
                is CreateTxException.CoinSelection -> "Coin selection issue."
                is CreateTxException.Descriptor -> "Descriptor issue."
                is CreateTxException.FeeRateTooLow -> "Fee rate specified is too low."
                is CreateTxException.FeeTooLow -> "The total fee is too low."
                is CreateTxException.InsufficientFunds -> "Insufficient funds available."
                is CreateTxException.LockTime -> "Lock time error."
                is CreateTxException.MiniscriptPsbt -> "Miniscript PSBT error."
                is CreateTxException.MissingKeyOrigin -> "Missing key origin information."
                is CreateTxException.MissingNonWitnessUtxo -> "Missing non-witness UTXO."
                is CreateTxException.NoRecipients -> "No recipients specified for the transaction."
                is CreateTxException.NoUtxosSelected -> "No UTXOs selected for the transaction."
                is CreateTxException.OutputBelowDustLimit -> "Transaction output is below the dust limit."
                is CreateTxException.Persist -> "Persistence issue occurred during transaction creation."
                is CreateTxException.Policy -> "Policy issue during transaction creation."
                is CreateTxException.Psbt -> "PSBT issue during transaction creation."
                is CreateTxException.RbfSequence -> "Replace-by-fee (RBF) sequence error."
                is CreateTxException.RbfSequenceCsv -> "RBF sequence with CSV error."
                is CreateTxException.SpendingPolicyRequired -> "Spending policy required but not provided."
                is CreateTxException.UnknownUtxo -> "Unknown UTXO encountered during transaction creation."
                is CreateTxException.Version0 -> "Version 0 specific issue."
                is CreateTxException.Version1Csv -> "Version 1 CSV specific issue."
                else -> exception.message
            }?.let { "BDK Transaction creation error: $it" }
        }

        class Descriptor(exception: DescriptorException) : BdkException {
            override val message = when (exception) {
                is DescriptorException.Base58 -> "Base58 encoding issue with the descriptor."
                is DescriptorException.Bip32 -> "BIP32 specific error with the descriptor."
                is DescriptorException.HardenedDerivationXpub -> "Error with hardened derivation in descriptor."
                is DescriptorException.Hex -> "Hexadecimal encoding issue with the descriptor."
                is DescriptorException.InvalidDescriptorCharacter -> "Invalid character found in descriptor."
                is DescriptorException.InvalidDescriptorChecksum -> "Descriptor checksum validation failed."
                is DescriptorException.InvalidHdKeyPath -> "Invalid HD key path in descriptor."
                is DescriptorException.Key -> "Key issue in descriptor."
                is DescriptorException.Miniscript -> "Miniscript issue with descriptor."
                is DescriptorException.MultiPath -> "Multipath issue in descriptor."
                is DescriptorException.Pk -> "Public key issue in descriptor."
                is DescriptorException.Policy -> "Policy issue in descriptor."
                else -> exception.message
            }?.let { "BDK Descriptor error: $it" }
        }

        class DescriptorKey(exception: DescriptorKeyException) : BdkException {
            override val message = when (exception) {
                is DescriptorKeyException.Bip32 -> "BIP32 key issue."
                is DescriptorKeyException.InvalidKeyType -> "Invalid key type encountered."
                is DescriptorKeyException.Parse -> "Error parsing the descriptor key."
                else -> exception.message
            }?.let { "BDK Descriptor key error: $it" }
        }

        class Electrum(exception: ElectrumException) : BdkException {
            override val message = when (exception) {
                is ElectrumException.AllAttemptsErrored -> "All attempts to connect to Electrum server failed."
                is ElectrumException.AlreadySubscribed -> "Already subscribed to the Electrum server."
                is ElectrumException.Bitcoin -> "Bitcoin-specific error with Electrum server."
                is ElectrumException.CouldNotCreateConnection -> "Failed to create a connection with Electrum server."
                is ElectrumException.CouldntLockReader -> "Unable to lock reader for Electrum server."
                is ElectrumException.Hex -> "Hexadecimal encoding issue with Electrum server response."
                is ElectrumException.InvalidDnsNameException -> "Invalid DNS name provided for Electrum server."
                is ElectrumException.InvalidResponse -> "Received an invalid response from the Electrum server."
                is ElectrumException.IoException -> "I/O error occurred with Electrum server."
                is ElectrumException.Json -> "JSON parsing error with Electrum server response."
                is ElectrumException.Message -> "Message-related error with Electrum server."
                is ElectrumException.MissingDomain -> "Missing domain in Electrum server configuration."
                is ElectrumException.Mpsc -> "MPSC-related error with Electrum server."
                is ElectrumException.NotSubscribed -> "Not subscribed to Electrum server."
                is ElectrumException.Protocol -> "Protocol error with Electrum server."
                is ElectrumException.RequestAlreadyConsumed -> "Request already consumed by Electrum server."
                is ElectrumException.SharedIoException -> "Shared I/O error occurred in Electrum server."
                else -> exception.message
            }?.let { "BDK Electrum error: $it" }
        }

        class Esplora(exception: EsploraException) : BdkException {
            override val message = when (exception) {
                is EsploraException.BitcoinEncoding -> "Bitcoin encoding error in Esplora."
                is EsploraException.HeaderHashNotFound -> "Header hash not found in Esplora response."
                is EsploraException.HeaderHeightNotFound -> "Header height not found in Esplora response."
                is EsploraException.HexToArray -> "Error converting hex to array in Esplora."
                is EsploraException.HexToBytes -> "Error converting hex to bytes in Esplora."
                is EsploraException.HttpResponse -> "HTTP response error from Esplora server."
                is EsploraException.InvalidHttpHeaderName -> "Invalid HTTP header name in Esplora response."
                is EsploraException.InvalidHttpHeaderValue -> "Invalid HTTP header value in Esplora response."
                is EsploraException.Minreq -> "Minimum requirements error in Esplora."
                is EsploraException.Parsing -> "Parsing error with Esplora data."
                is EsploraException.RequestAlreadyConsumed -> "Request already consumed by Esplora server."
                is EsploraException.StatusCode -> "Unexpected status code from Esplora server."
                is EsploraException.TransactionNotFound -> "Transaction not found in Esplora."
                else -> exception.message
            }?.let { "BDK Esplora error: $it" }
        }

        class ExtractTx(exception: ExtractTxException) : BdkException {
            override val message = when (exception) {
                is ExtractTxException.AbsurdFeeRate -> "Absurd fee rate encountered."
                is ExtractTxException.MissingInputValue -> "Missing input value."
                is ExtractTxException.OtherExtractTxErr -> "An unspecified error occurred."
                is ExtractTxException.SendingTooMuch -> "Sending amount exceeds allowable limits."
                else -> exception.message
            }?.let { "BDK Extract transaction error: $it" }
        }

        class FeeRate(exception: FeeRateException) : BdkException {
            override val message = when (exception) {
                is FeeRateException.ArithmeticOverflow -> "Arithmetic overflow occurred while calculating fee rate."
                else -> exception.message
            }?.let { "BDK Fee rate error: $it" }
        }

        class ParseAmount(exception: ParseAmountException) : BdkException {
            override val message = when (exception) {
                is ParseAmountException.InputTooLarge -> "Input amount is too large to parse."
                is ParseAmountException.InvalidCharacter -> "Invalid character found in amount parsing."
                is ParseAmountException.InvalidFormat -> "Amount format is invalid."
                is ParseAmountException.Negative -> "Negative amount encountered where not expected."
                is ParseAmountException.OtherParseAmountErr -> "An unspecified error occurred while parsing amount."
                is ParseAmountException.PossiblyConfusingDenomination -> "Confusing denomination in amount parsing."
                is ParseAmountException.TooBig -> "Amount is too large to process."
                is ParseAmountException.TooPrecise -> "Amount precision is too high."
                is ParseAmountException.UnknownDenomination -> "Unknown denomination encountered in amount parsing."
                else -> exception.message
            }?.let { "BDK Amount parsing error: $it" }
        }

        class Persistence(exception: PersistenceException) : BdkException {
            override val message = when (exception) {
                is PersistenceException.Write -> "Write operation failed in persistence layer."
                else -> exception.message
            }?.let { "BDK Persistence error: $it" }
        }

        class PsbtParse(exception: PsbtParseException) : BdkException {
            override val message = when (exception) {
                is PsbtParseException.Base64Encoding -> "Base64 encoding issue with PSBT."
                is PsbtParseException.PsbtEncoding -> "Error in PSBT encoding."
                else -> exception.message
            }?.let { "BDK PSBT parsing error: $it" }
        }

        class Signer(exception: SignerException) : BdkException {
            override val message = when (exception) {
                is SignerException.External -> "External signer error."
                is SignerException.InputIndexOutOfRange -> "Input index out of range during signing."
                is SignerException.InvalidKey -> "Invalid key encountered during signing."
                is SignerException.InvalidNonWitnessUtxo -> "Invalid non-witness UTXO encountered."
                is SignerException.InvalidSighash -> "Invalid sighash encountered during signing."
                is SignerException.MiniscriptPsbt -> "Miniscript PSBT issue during signing."
                is SignerException.MissingHdKeypath -> "Missing HD keypath in signing process."
                is SignerException.MissingKey -> "Missing key in signing process."
                is SignerException.MissingNonWitnessUtxo -> "Missing non-witness UTXO during signing."
                is SignerException.MissingWitnessScript -> "Missing witness script during signing."
                is SignerException.MissingWitnessUtxo -> "Missing witness UTXO during signing."
                is SignerException.NonStandardSighash -> "Non-standard sighash encountered."
                is SignerException.SighashException -> "Sighash exception encountered during signing."
                is SignerException.UserCanceled -> "Signing operation was canceled by the user."
                else -> exception.message
            }?.let { "BDK Signer error: $it" }
        }

        class Transaction(exception: TransactionException) : BdkException {
            override val message = when (exception) {
                is TransactionException.InvalidChecksum -> "Invalid checksum in transaction."
                is TransactionException.Io -> "I/O error occurred with transaction processing."
                is TransactionException.NonMinimalVarInt -> "Non-minimal VARINT encountered in transaction."
                is TransactionException.OtherTransactionErr -> "An unspecified transaction error occurred."
                is TransactionException.OversizedVectorAllocation -> "Vector allocation in transaction is oversized."
                is TransactionException.ParseFailed -> "Transaction parsing failed."
                is TransactionException.UnsupportedSegwitFlag -> "Unsupported SegWit flag encountered."
                else -> exception.message
            }?.let { "BDK Transaction error: $it" }
        }

        class TxidParse(exception: TxidParseException) : BdkException {
            override val message = when (exception) {
                is TxidParseException.InvalidTxid -> "Invalid TXID format encountered."
                else -> exception.message
            }?.let { "BDK TXID parsing error: $it" }
        }

        class WalletCreation(exception: WalletCreationException) : BdkException {
            override val message = when (exception) {
                is WalletCreationException.Descriptor -> "Descriptor error during wallet creation."
                is WalletCreationException.InvalidMagicBytes -> "Invalid magic bytes encountered in wallet creation."
                is WalletCreationException.Io -> "I/O error during wallet creation."
                is WalletCreationException.LoadedDescriptorDoesNotMatch -> "Loaded descriptor doesn't match expected."
                is WalletCreationException.LoadedGenesisDoesNotMatch -> "Loaded genesis block doesn't match expected."
                is WalletCreationException.LoadedNetworkDoesNotMatch -> "Loaded network doesn't match expected."
                is WalletCreationException.NotInitialized -> "Wallet has not been initialized."
                is WalletCreationException.Persist -> "Persistence issue encountered during wallet creation."
                else -> exception.message
            }?.let { "BDK Wallet creation error: $it" }
        }
    }
}
// endregion
