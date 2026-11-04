package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.HwFundingBroadcastResult
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
) {
    val signedTx: HwFundingSignedTx
        get() = HwFundingSignedTx(serializedTx, miningFeeSats, feeRate, totalSpent)

    fun broadcastResult(txId: String) = HwFundingBroadcastResult(txId, miningFeeSats, feeRate, totalSpent)

    override fun toString() = "HwRetainedSend(walletId='$walletId', retryWasRefused='$retryWasRefused')"
}

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

    /** Retaining the bytes already held keeps the existing record, including its `retryWasRefused`. */
    suspend fun retain(
        walletId: String,
        address: String,
        amountSats: ULong,
        signedTx: HwFundingSignedTx,
    ): Result<Unit> = locked {
        val existing = load(walletId)
        if (existing == null) {
            save(
                HwRetainedSend(
                    walletId = walletId,
                    address = address,
                    amountSats = amountSats,
                    serializedTx = signedTx.serializedTx,
                    miningFeeSats = signedTx.miningFeeSats,
                    feeRate = signedTx.feeRate,
                    totalSpent = signedTx.totalSpent,
                )
            )
        } else if (!existing.serializedTx.equals(signedTx.serializedTx, ignoreCase = true)) {
            throw HwRetainedSendError.DifferentTransactionRetained()
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

    /** Releases the wallet's record when one of [txIds] is its transaction. A record with invalid hex never matches. */
    suspend fun releaseIfObserved(walletId: String, txIds: Collection<String>): Result<Unit> = locked {
        val txId = load(walletId)?.let { runCatching { SignedTransactionId.fromHex(it.serializedTx) }.getOrNull() }
        if (txId != null && txIds.any { it.equals(txId, ignoreCase = true) }) keychain.deleteScoped(KEY, walletId)
    }

    /**
     * Only for a user who accepted a second payment. This is the one path that deletes an undecodable entry, otherwise
     * the wallet stays blocked until a full wipe. A failed read deletes nothing, because it says nothing about the
     * stored data and may recover on its own.
     */
    suspend fun abandon(walletId: String): Result<Unit> = locked {
        runCatching { load(walletId) }.onFailure { if (it !is HwRetainedSendError.Undecodable) throw it }
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
