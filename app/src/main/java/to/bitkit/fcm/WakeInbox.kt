package to.bitkit.fcm

import com.synonym.bitkitcore.WakeAckOutcome
import com.synonym.bitkitcore.WakeEnvelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.bitkit.data.ReceivedWake
import to.bitkit.data.WakeCredentialStore
import to.bitkit.data.WakeStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.repositories.WakeConfig
import to.bitkit.services.WakeService
import to.bitkit.services.wakeRejection
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

/**
 * Receives the wakes that arrive in pushes: decrypts each, does its work once, drops it past its deadline, and
 * acknowledges it. Delivery is at least once and the process can die between two deliveries, so the wakes received
 * are kept in [WakeStore] until their deadline.
 */
@OptIn(ExperimentalTime::class)
@Singleton
class WakeInbox @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val wakeService: WakeService,
    private val wakeConfig: WakeConfig,
    private val credentialStore: WakeCredentialStore,
    private val wakeStore: WakeStore,
    private val handlers: WakeHandlers,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "WakeInbox"
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** How long a wake without a deadline, which only a legacy wake lacks, is remembered. */
        private val DEFAULT_RETENTION = 1.days
    }

    private val mutex = Mutex()

    /**
     * Handles the wake in [pushJson], the FCM data map as JSON. Fails only when the acknowledgement did not reach the
     * gateway, so the caller can try again: a second delivery of a handled wake acknowledges it without its work.
     */
    suspend fun receive(pushJson: String): Result<WakeReceipt> = withContext(ioDispatcher) {
        mutex.withLock {
            runSuspendCatching {
                val gatewayUrl = wakeConfig.gatewayUrl ?: return@runSuspendCatching WakeReceipt.Ignored
                val credentials = credentialStore.load() ?: return@runSuspendCatching WakeReceipt.Ignored
                val wake = decrypt(credentials.encryptionSecretKey, pushJson)
                    ?: return@runSuspendCatching WakeReceipt.Undecryptable
                process(wake, gatewayUrl, credentials.deviceSecret)
            }
        }
    }

    private suspend fun decrypt(secretKeyHex: String, pushJson: String): WakeEnvelope? =
        runSuspendCatching { wakeService.decryptPush(secretKeyHex, pushJson) }
            .onFailure { Logger.warn("Dropped a push that could not be decrypted", it, context = TAG) }
            .getOrNull()

    private suspend fun process(wake: WakeEnvelope, gatewayUrl: String, deviceSecret: String): WakeReceipt {
        val id = wake.id
        if (wake.fallback || id == null) return WakeReceipt.NothingToAck

        val received = wakeStore.received(id)
        if (received != null) {
            if (received.acked) return WakeReceipt.Duplicate
            val outcome = WakeAckOutcome.valueOf(received.outcome)
            acknowledge(gatewayUrl, deviceSecret, id, outcome)
            return WakeReceipt.Acked(outcome)
        }

        val nowSecs = clock.now().epochSeconds
        val deadlineSecs = wake.deadline?.toLong()
        if (deadlineSecs != null && nowSecs > deadlineSecs) return WakeReceipt.Stale

        val outcome = handlers.handle(wake)
        val retainUntilSecs = deadlineSecs ?: (nowSecs + DEFAULT_RETENTION.inWholeSeconds)
        wakeStore.saveReceived(ReceivedWake(id = id, deadlineSecs = retainUntilSecs, outcome = outcome.name), nowSecs)
        acknowledge(gatewayUrl, deviceSecret, id, outcome)
        return WakeReceipt.Acked(outcome)
    }

    private suspend fun acknowledge(gatewayUrl: String, deviceSecret: String, id: String, outcome: WakeAckOutcome) {
        runSuspendCatching { wakeService.ack(gatewayUrl, deviceSecret, id, outcome) }
            .recoverCatching { if (!it.isFinalAckRejection()) throw it }
            .getOrThrow()
        wakeStore.markAcked(id)
    }

    private fun Throwable.isFinalAckRejection(): Boolean {
        val status = wakeRejection()?.status?.toInt() ?: return false
        return status in 400..499 && status != HTTP_TOO_MANY_REQUESTS
    }
}

/** What became of a push. */
sealed interface WakeReceipt {
    /** Wakes are off, or this install has no keys to decrypt with. */
    data object Ignored : WakeReceipt

    /** The push held nothing this install can decrypt, such as one encrypted to a key it replaced. */
    data object Undecryptable : WakeReceipt

    /** A fallback alert, which the system shows, or a legacy wake: nothing to do or acknowledge. */
    data object NothingToAck : WakeReceipt

    /** A wake already handled and acknowledged. */
    data object Duplicate : WakeReceipt

    /** A wake past its deadline, dropped without its work. */
    data object Stale : WakeReceipt

    data class Acked(val outcome: WakeAckOutcome) : WakeReceipt
}

/** The FCM data map as the JSON object `wakeDecryptPush` reads. Every v1 wake arrives as a data message. */
internal fun wakePushJson(data: Map<String, String>): String =
    JsonObject(data.mapValues { JsonPrimitive(it.value) }).toString()
