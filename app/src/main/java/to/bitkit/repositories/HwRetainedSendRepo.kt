package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HwFundingSignedTx
import to.bitkit.utils.AppError
import to.bitkit.utils.SignedTransactionId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A signed ordinary hardware-wallet send whose broadcast may have gone out. Rebroadcasting these same bytes cannot pay
 * twice, because the txid stays the same, while a new signature can create a second payment.
 * [serializedTx] and [address] reveal the payment, so [toString] leaves them out.
 */
@Serializable
data class HwRetainedSend(
    val walletId: String,
    val address: String,
    val amountSats: ULong,
    val serializedTx: String,
    val miningFeeSats: ULong,
    val feeRate: ULong,
    val totalSpent: ULong,
    val retryWasRefused: Boolean = false,
    val observed: Boolean = false,
) {
    val signedTx: HwFundingSignedTx
        get() = HwFundingSignedTx(serializedTx, miningFeeSats, feeRate, totalSpent)

    fun isSamePayment(address: String, amountSats: ULong) = this.address == address && this.amountSats == amountSats

    /**
     * Whether a payment to [address] may send these bytes again. A refused retry or a payment already on the network
     * is not a retry, so it gets no exemption from today's maximum or fee.
     */
    fun offersRetryTo(address: String) = !retryWasRefused && !observed && this.address == address

    override fun toString() =
        "HwRetainedSend(walletId='$walletId', retryWasRefused='$retryWasRefused', observed='$observed')"
}

/** Which existing record [HwRetainedSendRepo.retain] may overwrite with different bytes. */
enum class HwRetainedSendReplacement {
    None,

    /** Only a record the watcher has seen, because that payment is resolved. Checked again at write time. */
    ObservedRecord,

    /** The user accepted a second payment, so anything stored may go, including an entry that cannot be read. */
    Consented,
}

/** What [HwRetainedSendRepo.retain] did to the wallet's entry, so a send that never goes out can undo exactly that. */
sealed interface HwRetainOutcome {
    data object Stored : HwRetainOutcome
    data object AlreadyHeld : HwRetainOutcome
    data class Replaced(val previous: HwRetainedSend) : HwRetainOutcome
    data object ReplacedUnreadableEntry : HwRetainOutcome
}

/** What [HwRetainedSendRepo.restore] did to the wallet's entry. A failed read or write fails the `Result` instead. */
enum class HwRestoreOutcome { WroteOldRecord, LeftCurrentRecord }

sealed class HwRetainedSendError(message: String) : AppError(message) {
    class DifferentTransactionRetained : HwRetainedSendError("A different signed transaction is already retained")

    /** Carries no cause, because a decoder's message can quote the entry, and those bytes reveal the payment. */
    class Undecodable : HwRetainedSendError("The retained send entry cannot be decoded")
}

/**
 * Holds at most one retained send per hardware wallet, in device-only storage that `Keychain.wipe` clears and backups
 * never read. Every read fails on unreadable storage rather than reporting no record, because a missing record lets the
 * next attempt sign again.
 */
@Singleton
class HwRetainedSendRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
) {
    companion object {
        private val KEY = Keychain.Key.HW_RETAINED_SEND.name
        private val storeJson = Json { ignoreUnknownKeys = true }
    }

    private val mutex = Mutex()

    /**
     * Retaining the bytes already held keeps the existing record, including its `retryWasRefused`. A replacement is one
     * write, so a failed one leaves the old record as it was.
     */
    suspend fun retain(
        walletId: String,
        address: String,
        amountSats: ULong,
        signedTx: HwFundingSignedTx,
        replacement: HwRetainedSendReplacement = HwRetainedSendReplacement.None,
    ): Result<HwRetainOutcome> = locked {
        val send = HwRetainedSend(
            walletId = walletId,
            address = address,
            amountSats = amountSats,
            serializedTx = signedTx.serializedTx,
            miningFeeSats = signedTx.miningFeeSats,
            feeRate = signedTx.feeRate,
            totalSpent = signedTx.totalSpent,
        )
        val existing = runSuspendCatching { load(walletId) }.getOrElse {
            // Keychain wraps decrypt failures as FailedToLoad, so an undecryptable entry looks like a read failure.
            val isUnreadable = it is HwRetainedSendError.Undecodable || it is KeychainError.FailedToLoad
            if (replacement != HwRetainedSendReplacement.Consented || !isUnreadable) throw it
            save(send)
            return@locked HwRetainOutcome.ReplacedUnreadableEntry
        }
        when {
            existing == null -> {
                save(send)
                HwRetainOutcome.Stored
            }
            existing.serializedTx.equals(send.serializedTx, ignoreCase = true) -> HwRetainOutcome.AlreadyHeld
            replacement == HwRetainedSendReplacement.Consented ||
                replacement == HwRetainedSendReplacement.ObservedRecord && existing.observed -> {
                save(send)
                HwRetainOutcome.Replaced(existing)
            }
            else -> throw HwRetainedSendError.DifferentTransactionRetained()
        }
    }

    /**
     * Puts back the record that [serializedTx] replaced, when those bytes never reached dispatch or settled as not
     * sent. Bytes the watcher has seen did go out, so they stay as the record.
     */
    suspend fun restore(previous: HwRetainedSend, serializedTx: String): Result<HwRestoreOutcome> = locked {
        val current = load(previous.walletId)
        if (current?.serializedTx.equals(serializedTx, ignoreCase = true) && current?.observed == false) {
            save(previous)
            HwRestoreOutcome.WroteOldRecord
        } else {
            HwRestoreOutcome.LeftCurrentRecord
        }
    }

    suspend fun record(walletId: String): Result<HwRetainedSend?> = locked { load(walletId) }

    suspend fun markRetryRefused(walletId: String): Result<Unit> = locked {
        load(walletId)?.takeUnless { it.retryWasRefused }?.let { save(it.copy(retryWasRefused = true)) }
    }

    /** Reads first so an undecodable entry fails and stays in place. Only [abandon] may delete one. */
    suspend fun release(walletId: String): Result<Unit> = locked {
        if (load(walletId) != null) keychain.deleteScoped(KEY, walletId)
    }

    /**
     * Marks the wallet's record when one of [txIds] is its transaction. A record with invalid hex never matches.
     * Never deletes: a delivered transaction is when the sign button still reads Retry, and with no record to restore
     * that retry would sign a second payment.
     */
    suspend fun markObserved(walletId: String, txIds: Collection<String>): Result<Unit> = locked {
        val record = load(walletId)?.takeUnless { it.observed } ?: return@locked
        val txId = runCatching { SignedTransactionId.fromHex(record.serializedTx) }.getOrNull() ?: return@locked
        if (txIds.any { it.equals(txId, ignoreCase = true) }) save(record.copy(observed = true))
    }

    /**
     * Only for a user who removed the wallet. This is the one path that deletes an entry it cannot read, otherwise
     * re-pairing the wallet brings the warning back.
     */
    suspend fun abandon(walletId: String): Result<Unit> = locked {
        // Keychain wraps decrypt failures as FailedToLoad, so an undecryptable entry looks like a plain read failure.
        keychain.deleteScoped(KEY, walletId)
    }

    private suspend fun <T> locked(block: suspend () -> T): Result<T> = withContext(ioDispatcher) {
        runSuspendCatching { mutex.withLock { block() } }
    }

    private fun load(walletId: String): HwRetainedSend? {
        val entry = keychain.loadScopedString(KEY, walletId) ?: return null
        return runCatching { storeJson.decodeFromString<HwRetainedSend>(entry) }
            .getOrElse { throw HwRetainedSendError.Undecodable() }
    }

    private suspend fun save(send: HwRetainedSend) =
        keychain.upsertScopedString(KEY, storeJson.encodeToString(send), send.walletId)
}
