package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.utils.AppError
import to.bitkit.services.LightningService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OnchainSendOutcome {
    val txid: String

    data class Accepted(override val txid: String) : OnchainSendOutcome
    data class Rejected(override val txid: String, val reason: String) : OnchainSendOutcome
    data class Unknown(override val txid: String) : OnchainSendOutcome
}

@Serializable
enum class OnchainSendEvidence {
    Pending,
    Accepted,
    Rejected,
    Unknown,
    Observed,
}

@Serializable
@Suppress("LongParameterList")
data class OnchainSendAttempt(
    val walletId: String,
    val attemptId: String,
    val requestId: PaykitPaymentRequestId?,
    val orderId: String?,
    val address: String,
    val amountSats: ULong,
    val isMaxAmount: Boolean,
    val feeRateSatsPerVByte: ULong,
    val isTransfer: Boolean,
    val channelId: String?,
    val tags: List<String>,
    val evidence: OnchainSendEvidence = OnchainSendEvidence.Pending,
    val txid: String? = null,
    val refusalReason: String? = null,
    val localFollowupComplete: Boolean = false,
    val walletIndex: Int = 0,
) {
    val isUnresolved: Boolean
        get() = evidence == OnchainSendEvidence.Pending ||
            evidence == OnchainSendEvidence.Rejected ||
            evidence == OnchainSendEvidence.Unknown

    val hasPositiveEvidence: Boolean
        get() = evidence == OnchainSendEvidence.Accepted || evidence == OnchainSendEvidence.Observed

    val blocksNextSend: Boolean
        get() = isUnresolved || (hasPositiveEvidence && !localFollowupComplete)
}

class OnchainSendBlockedError(val attempt: OnchainSendAttempt? = null) :
    AppError("A previous on-chain send is unresolved or this payment was already sent.")

class OnchainSendAttemptUnreadableError(cause: Throwable) : AppError("Failed to read on-chain send attempt", cause)

class OnchainSendNotDispatchedError(cause: Throwable) : AppError("On-chain send did not reach the backend", cause)

class OnchainSendPendingError(cause: Throwable, val txid: String? = null) :
    AppError("On-chain send outcome is unknown; do not send again", cause)

@Singleton
class OnchainSendAttemptStore @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val keychain: Keychain,
    private val lightningService: LightningService,
) {
    companion object {
        private val KEY = Keychain.Key.ONCHAIN_SEND_ATTEMPT.name
    }

    private val mutex = Mutex()

    suspend fun current(): OnchainSendAttempt? = withContext(ioDispatcher) {
        mutex.withLock { load(lightningService.currentWalletIndex) }
    }

    @Suppress("LongParameterList")
    suspend fun admit(
        walletId: String,
        requestId: PaykitPaymentRequestId?,
        orderId: String?,
        address: String,
        amountSats: ULong,
        isMaxAmount: Boolean,
        feeRateSatsPerVByte: ULong,
        isTransfer: Boolean,
        channelId: String?,
        tags: List<String>,
        beforeSendAttempt: suspend () -> Unit,
    ): OnchainSendAttempt = withContext(ioDispatcher) {
        mutex.withLock {
            val walletIndex = lightningService.currentWalletIndex
            val previous = load(walletIndex)
            if (previous != null && (
                    previous.walletId != walletId ||
                        previous.blocksNextSend ||
                        (requestId != null && previous.requestId == requestId) ||
                        (orderId != null && previous.orderId == orderId)
                    )
            ) {
                throw OnchainSendBlockedError(previous)
            }
            beforeSendAttempt()
            val attempt = OnchainSendAttempt(
                walletId = walletId,
                attemptId = UUID.randomUUID().toString(),
                requestId = requestId,
                orderId = orderId,
                address = address,
                amountSats = amountSats,
                isMaxAmount = isMaxAmount,
                feeRateSatsPerVByte = feeRateSatsPerVByte,
                isTransfer = isTransfer,
                channelId = channelId,
                tags = tags,
                walletIndex = walletIndex,
            )
            persist(attempt)
            attempt
        }
    }

    suspend fun recordOutcome(attemptId: String, outcome: OnchainSendOutcome, walletIndex: Int): OnchainSendAttempt =
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                val current = load(walletIndex)
                if (current?.attemptId != attemptId) throw OnchainSendBlockedError()
                val evidence = when (outcome) {
                    is OnchainSendOutcome.Accepted -> OnchainSendEvidence.Accepted
                    is OnchainSendOutcome.Rejected -> OnchainSendEvidence.Rejected
                    is OnchainSendOutcome.Unknown -> OnchainSendEvidence.Unknown
                }
                current.copy(
                    evidence = evidence,
                    txid = outcome.txid,
                    refusalReason = (outcome as? OnchainSendOutcome.Rejected)?.reason,
                ).also { persist(it) }
            }
        }

    suspend fun releaseBeforeDispatch(attemptId: String, walletIndex: Int) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = load(walletIndex)
            if (current?.attemptId == attemptId && current.evidence == OnchainSendEvidence.Pending) {
                keychain.delete(KEY, walletIndex)
            }
        }
    }

    suspend fun markLocalFollowupComplete(attemptId: String, walletIndex: Int) = withContext(ioDispatcher + NonCancellable) {
        mutex.withLock {
            val current = load(walletIndex)
            if (current?.attemptId == attemptId && current.hasPositiveEvidence) {
                persist(current.copy(localFollowupComplete = true))
            }
        }
    }

    suspend fun observeExactTransaction(txid: String): OnchainSendAttempt? =
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                val current = load(lightningService.currentWalletIndex) ?: return@withLock null
                if (!current.txid.equals(txid, ignoreCase = true) || !current.isUnresolved) return@withLock null
                current.copy(evidence = OnchainSendEvidence.Observed).also { persist(it) }
            }
        }

    private fun load(walletIndex: Int): OnchainSendAttempt? {
        val value = keychain.loadString(KEY, walletIndex) ?: return null
        return runCatching {
            Json.decodeFromString<OnchainSendAttempt>(value).also { attempt ->
                require(attempt.walletId.isNotBlank() && attempt.attemptId.isNotBlank() && attempt.walletIndex == walletIndex)
                require(attempt.evidence == OnchainSendEvidence.Pending ||
                    attempt.txid?.matches(Regex("[0-9a-fA-F]{64}")) == true)
                require(!attempt.localFollowupComplete || attempt.hasPositiveEvidence)
            }
        }
            .getOrElse { throw OnchainSendAttemptUnreadableError(it) }
    }

    private suspend fun persist(attempt: OnchainSendAttempt) {
        keychain.upsertString(KEY, Json.encodeToString(attempt), attempt.walletIndex)
    }
}
