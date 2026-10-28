package to.bitkit.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.R
import to.bitkit.data.keychain.Keychain
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class PrimaryWalletOperationKind { Open, SpliceIn, SpliceOut }

@Serializable
enum class PrimaryWalletOperationStage { Prepared, Dispatched, Submitted, Ready, FailureAwaitingAck, Closed, Failed }

@Serializable
data class PrimaryWalletOperation(
    val id: String,
    val walletIndex: Int,
    val primaryNodeId: String,
    val kind: PrimaryWalletOperationKind,
    val amountSats: ULong,
    val walletId: String,
    val feeRate: ULong = 0uL,
    val stage: PrimaryWalletOperationStage = PrimaryWalletOperationStage.Prepared,
    val userChannelId: String? = null,
    val previousFundingTxid: String? = null,
    val fundingTxid: String? = null,
    val address: String? = null,
    val failure: String? = null,
    val failureEventFingerprint: String? = null,
    val failureNodeInstanceId: String? = null,
    val localFollowupComplete: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    val blocksWallet: Boolean
        get() = when (stage) {
            PrimaryWalletOperationStage.Failed -> false
            PrimaryWalletOperationStage.Ready,
            PrimaryWalletOperationStage.Closed,
            -> kind == PrimaryWalletOperationKind.SpliceOut && !localFollowupComplete
            else -> true
        }
}

@Singleton
class PrimaryWalletOperationStore @Inject constructor(private val keychain: Keychain) {
    companion object {
        private val KEY = Keychain.Key.PRIMARY_WALLET_OPERATION.name
        private val FUNDING_KEY = Keychain.Key.PRIMARY_WALLET_FUNDING_TRANSACTIONS.name
    }

    private val mutex = Mutex()
    private val _version = MutableStateFlow(0L)
    val version = _version.asStateFlow()

    suspend fun load(walletIndex: Int): PrimaryWalletOperation? = mutex.withLock { read(walletIndex) }

    suspend fun isFundingTransaction(walletIndex: Int, walletId: String, txid: String): Boolean = mutex.withLock {
        read(walletIndex)?.let { preserveFundingTransaction(it) }
        txid in readFundingTransactions(walletIndex)[walletId].orEmpty()
    }

    suspend fun update(
        walletIndex: Int,
        transform: (PrimaryWalletOperation?) -> PrimaryWalletOperation,
    ): PrimaryWalletOperation = mutex.withLock {
        val previous = read(walletIndex)
        val next = transform(previous)
        check(next.walletIndex == walletIndex)
        previous?.let { preserveFundingTransaction(it) }
        preserveFundingTransaction(next)
        keychain.upsertString(KEY, Json.encodeToString(next), walletIndex)
        _version.update { it + 1 }
        next
    }

    private suspend fun preserveFundingTransaction(operation: PrimaryWalletOperation) {
        val txid = operation.fundingTxid ?: return
        if (operation.kind == PrimaryWalletOperationKind.SpliceOut) return
        val history = readFundingTransactions(operation.walletIndex)
        val walletTransactions = history[operation.walletId].orEmpty()
        if (txid in walletTransactions) return
        keychain.upsertString(
            FUNDING_KEY,
            Json.encodeToString(history + (operation.walletId to (walletTransactions + txid))),
            operation.walletIndex,
        )
    }

    private fun readFundingTransactions(walletIndex: Int): Map<String, Set<String>> {
        val serialized = keychain.loadString(FUNDING_KEY, walletIndex) ?: return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, Set<String>>>(serialized) }
            .getOrElse { throw PrimaryWalletError.StateUnreadable(it) }
    }

    private fun read(walletIndex: Int): PrimaryWalletOperation? {
        val serialized = keychain.loadString(KEY, walletIndex) ?: return null
        return runCatching {
            Json.decodeFromString<PrimaryWalletOperation>(serialized).also {
                require(it.walletIndex == walletIndex && it.walletId.isNotBlank() && it.id.isNotBlank())
                require(it.primaryNodeId.isNotBlank())
                require(it.amountSats > 0uL)
                require(it.kind != PrimaryWalletOperationKind.SpliceOut || !it.address.isNullOrBlank())
                require(it.stage != PrimaryWalletOperationStage.Submitted || !it.fundingTxid.isNullOrBlank())
                require(
                    it.stage != PrimaryWalletOperationStage.FailureAwaitingAck ||
                        !it.failureEventFingerprint.isNullOrBlank(),
                )
            }
        }.getOrElse { throw PrimaryWalletError.StateUnreadable(it) }
    }
}

class PrimaryWalletPendingError(val operation: PrimaryWalletOperation) :
    PrimaryWalletError(R.string.wallet_primary__pending)

class PrimaryWalletDispatchRejected(message: String, cause: Throwable? = null) :
    PrimaryWalletError(R.string.primary_node__payment_failed, message, cause)
