package to.bitkit.repositories

import com.google.firebase.messaging.FirebaseMessaging
import com.synonym.bitkitcore.WakeDevice
import com.synonym.bitkitcore.WakeIdentityProof
import com.synonym.bitkitcore.WakeRegistration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import to.bitkit.async.appScope
import to.bitkit.data.WakeCredentialStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.toHex
import to.bitkit.services.WakeCredentials
import to.bitkit.services.WakeService
import to.bitkit.services.wakeRejection
import to.bitkit.utils.Logger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Registers this install with the wake gateway and keeps the registration in step, holds a presence lease while the
 * app is open, and unregisters on opt-out and wipe. Does nothing unless [WakeConfig] turns wakes on.
 */
@OptIn(ExperimentalTime::class)
@Singleton
class WakeRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val wakeService: WakeService,
    private val wakeConfig: WakeConfig,
    private val credentialStore: WakeCredentialStore,
    private val lightningRepo: LightningRepo,
    private val pubkyRepo: PubkyRepo,
    private val firebaseMessaging: FirebaseMessaging,
    private val clock: Clock,
) {
    companion object {
        private const val TAG = "WakeRepo"
        private const val HTTP_UNAUTHORIZED = 401

        /**
         * The topics this install subscribes to. Patterns, so a topic the gateway adds to one of these namespaces
         * arrives without an app release. `wake.test` needs no subscription, since the gateway sends it only to the
         * device that asked for it, and `blocktank.*` is left out because nothing sends it.
         */
        val TOPICS = listOf("paykit.*", "swap.*")

        /** How often the presence lease is renewed while the app is open. */
        val PRESENCE_RENEW_INTERVAL = 60.seconds

        /** How long one presence lease lasts, longer than [PRESENCE_RENEW_INTERVAL] so a late renewal keeps it. */
        val PRESENCE_TTL = 90.seconds
    }

    private val scope = appScope(ioDispatcher, TAG)
    private val registrationMutex = Mutex()
    private val presenceMutex = Mutex()

    @Volatile
    private var registrationJob: Job? = null

    @Volatile
    private var presenceJob: Job? = null

    /**
     * Keeps the registration in step with the node, the opt-in and the Pubky identity from now on, and holds a
     * presence lease until [onAppBackground], so the gateway holds back fallback alerts while the app is open.
     */
    fun onAppForeground() {
        if (wakeConfig.gatewayUrl == null) return
        if (registrationJob?.isActive != true) registrationJob = scope.launch { observeRegistration() }
        if (presenceJob?.isActive != true) presenceJob = scope.launch { holdPresence() }
    }

    /** Ends the presence lease once the app leaves the foreground. */
    fun onAppBackground() {
        if (wakeConfig.gatewayUrl == null) return
        presenceJob?.cancel()
        presenceJob = null
        scope.launch { clearPresence() }
    }

    fun syncRegistrationAsync(pushToken: String) = scope.launch { syncRegistration(pushToken) }

    /**
     * Registers this install, updates its registration or, after an opt-out, unregisters it. Skips the gateway when
     * nothing changed since the registration it last accepted, unless [force] is set.
     */
    suspend fun syncRegistration(pushToken: String? = null, force: Boolean = false): Result<WakeSync> =
        withContext(ioDispatcher) {
            registrationMutex.withLock {
                runSuspendCatching { sync(pushToken, force) }
                    .onSuccess { Logger.debug("Synced wake registration '$it'", context = TAG) }
                    .onFailure { Logger.warn("Failed to sync wake registration", it, context = TAG) }
            }
        }

    /** Unregisters this install so its device secret stops working. Best effort, for the wallet wipe. */
    suspend fun unregister(): Result<Unit> = withContext(ioDispatcher) {
        registrationMutex.withLock {
            runSuspendCatching {
                val gatewayUrl = wakeConfig.gatewayUrl ?: return@runSuspendCatching
                val credentials = credentialStore.load() ?: return@runSuspendCatching
                unregisterDevice(gatewayUrl, credentials)
            }
        }
    }

    /** The device as the gateway stores it. */
    suspend fun getDevice(): Result<WakeDevice> = withContext(ioDispatcher) {
        runSuspendCatching {
            val device = requireDevice()
            wakeService.getDevice(device.gatewayUrl, device.credentials.deviceSecret)
        }
    }

    /** Asks the gateway to send `wake.test` to this device alone. Returns the wake id. */
    suspend fun sendTest(): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            val device = requireDevice()
            wakeService.sendTest(device.gatewayUrl, device.credentials.deviceSecret)
        }
    }

    private suspend fun sync(pushToken: String?, force: Boolean): WakeSync {
        val gatewayUrl = wakeConfig.gatewayUrl
        if (gatewayUrl == null || !wakeConfig.isRegistrationEnabled) return WakeSync.Off
        if (!wakeConfig.isOptedIn()) return optOut(gatewayUrl)
        val nodeId = lightningRepo.getNodeId() ?: return WakeSync.Waiting
        val pubkyIdentity = pubkyRepo.wakeIdentity().getOrElse {
            Logger.warn("Skipped wake registration until the Pubky identity can be read", it, context = TAG)
            return WakeSync.Waiting
        }
        val registration = PendingRegistration(
            gatewayUrl = gatewayUrl,
            pushToken = pushToken ?: firebaseMessaging.token.await(),
            credentials = credentialStore.loadOrCreate(),
            lightningIdentity = lightningWakeIdentity(nodeId),
            pubkyIdentity = pubkyIdentity,
        )
        val fingerprint = registration.fingerprint()
        if (!force && credentialStore.registrationState().fingerprint == fingerprint) return WakeSync.UpToDate

        val accepted = register(registration)
        credentialStore.updateRegistrationState { it.copy(fingerprint = fingerprint) }
        if (presenceJob?.isActive == true) scope.launch { setPresence() }
        return WakeSync.Registered(deviceId = accepted.deviceId, unknownTopics = accepted.unknownTopics)
    }

    private suspend fun register(registration: PendingRegistration): WakeRegistration {
        val audience = wakeService.serverInfo(registration.gatewayUrl).audience
        val timestampSecs = nextRegistrationTimestamp(
            nowSecs = clock.now().epochSeconds,
            lastSecs = credentialStore.registrationState().lastTimestampSecs,
        )
        return runSuspendCatching { registerAt(registration, audience, timestampSecs) }.getOrElse {
            val serverTimeSecs = it.staleTimestampServerTime() ?: throw it
            Logger.info("Retrying wake registration at the gateway time '$serverTimeSecs'", context = TAG)
            registerAt(registration, audience, serverTimeSecs)
        }
    }

    private suspend fun registerAt(
        registration: PendingRegistration,
        audience: String,
        timestampSecs: Long,
    ): WakeRegistration {
        val request = wakeService.prepareRegistration(
            audience = audience,
            credentials = registration.credentials,
            pushToken = registration.pushToken,
            identities = registration.identities,
            topics = TOPICS,
            timestampSecs = timestampSecs,
        )
        credentialStore.updateRegistrationState { it.copy(lastTimestampSecs = timestampSecs) }
        val lightningProof = WakeIdentityProof(
            identity = registration.lightningIdentity,
            signature = lightningRepo.signMessage(request.message).getOrThrow(),
        )
        val pubkyProof = registration.pubkyIdentity?.let {
            WakeIdentityProof(
                identity = it,
                signature = pubkyRepo.signWakeRegistration(it, request.message).getOrThrow(),
            )
        }
        return wakeService.register(registration.gatewayUrl, request, listOfNotNull(lightningProof, pubkyProof))
    }

    private suspend fun optOut(gatewayUrl: String): WakeSync {
        if (credentialStore.registrationState().fingerprint == null) return WakeSync.NotOptedIn
        val credentials = credentialStore.load() ?: return WakeSync.NotOptedIn
        unregisterDevice(gatewayUrl, credentials)
        return WakeSync.Unregistered
    }

    private suspend fun unregisterDevice(gatewayUrl: String, credentials: WakeCredentials) {
        runSuspendCatching { wakeService.unregister(gatewayUrl, credentials.deviceSecret) }
            .recoverCatching { if (it.wakeRejection()?.status?.toInt() != HTTP_UNAUTHORIZED) throw it }
            .getOrThrow()
        credentialStore.updateRegistrationState { it.copy(fingerprint = null) }
    }

    private suspend fun observeRegistration() {
        combine(
            lightningRepo.lightningState.map { it.nodeLifecycleState.isRunning() },
            wakeConfig.optedIn,
            pubkyRepo.publicKey,
        ) { isNodeRunning, isOptedIn, publicKey -> Triple(isNodeRunning, isOptedIn, publicKey) }
            .distinctUntilChanged()
            .collect { syncRegistration() }
    }

    private suspend fun holdPresence() {
        while (currentCoroutineContext().isActive) {
            setPresence()
            delay(PRESENCE_RENEW_INTERVAL)
        }
    }

    private suspend fun setPresence() {
        presenceMutex.withLock {
            runSuspendCatching {
                val device = registeredDevice() ?: return@runSuspendCatching
                val ttlSecs = PRESENCE_TTL.inWholeSeconds.toInt()
                val expiresAtSecs = wakeService.setPresence(device.gatewayUrl, device.credentials.deviceSecret, ttlSecs)
                Logger.verbose("Set wake presence until '$expiresAtSecs'", context = TAG)
            }.onFailure { Logger.warn("Failed to set wake presence", it, context = TAG) }
        }
    }

    private suspend fun clearPresence() {
        presenceMutex.withLock {
            if (presenceJob?.isActive == true) return
            runSuspendCatching {
                val device = registeredDevice() ?: return@runSuspendCatching
                wakeService.clearPresence(device.gatewayUrl, device.credentials.deviceSecret)
            }.onFailure { Logger.warn("Failed to clear wake presence", it, context = TAG) }
        }
    }

    private suspend fun registeredDevice(): RegisteredDevice? {
        val gatewayUrl = wakeConfig.gatewayUrl ?: return null
        if (credentialStore.registrationState().fingerprint == null) return null
        val credentials = credentialStore.load() ?: return null
        return RegisteredDevice(gatewayUrl, credentials)
    }

    private suspend fun requireDevice(): RegisteredDevice {
        val gatewayUrl = checkNotNull(wakeConfig.gatewayUrl) { "No wake gateway in this build" }
        val credentials = checkNotNull(credentialStore.load()) { "Not registered for wakes" }
        return RegisteredDevice(gatewayUrl, credentials)
    }

    private fun Throwable.staleTimestampServerTime(): Long? = wakeRejection()
        ?.takeIf { it.status.toInt() == HTTP_UNAUTHORIZED && it.code == WakeService.STALE_TIMESTAMP }
        ?.serverTime
        ?.toLong()

    private class RegisteredDevice(val gatewayUrl: String, val credentials: WakeCredentials)

    private class PendingRegistration(
        val gatewayUrl: String,
        val pushToken: String,
        val credentials: WakeCredentials,
        val lightningIdentity: String,
        val pubkyIdentity: String?,
    ) {
        val identities: List<String> get() = listOfNotNull(lightningIdentity, pubkyIdentity)

        fun fingerprint(): String = wakeRegistrationFingerprint(gatewayUrl, pushToken, credentials, identities, TOPICS)
    }
}

/** What [WakeRepo.syncRegistration] did. */
sealed interface WakeSync {
    /** Wakes are off in this build, or it is an E2E build. */
    data object Off : WakeSync

    /** The user has not opted in, and nothing is registered. */
    data object NotOptedIn : WakeSync

    /** The user opted out, so the registration was removed. */
    data object Unregistered : WakeSync

    /** Waiting for the node to run or the Pubky identity to be readable, which triggers the next attempt. */
    data object Waiting : WakeSync

    /** Nothing changed since the registration the gateway last accepted. */
    data object UpToDate : WakeSync

    data class Registered(val deviceId: String, val unknownTopics: List<String>) : WakeSync
}

/** `max(now, last + 1)`: the gateway refuses a registration older than the install's last one with a 409. */
internal fun nextRegistrationTimestamp(nowSecs: Long, lastSecs: Long?): Long =
    if (lastSecs == null) nowSecs else maxOf(nowSecs, lastSecs + 1)

internal fun lightningWakeIdentity(nodeId: String): String = "ln:$nodeId"

/** The `pk:` wake identity of a Pubky public key: the gateway wants the 52 bare characters, without `pubky`. */
internal fun pubkyWakeIdentity(publicKey: String): String = "pk:${publicKey.removePrefix("pubky")}"

/** A hash of all a registration sends but its timestamp, to tell whether anything changed since the last one. */
internal fun wakeRegistrationFingerprint(
    gatewayUrl: String,
    pushToken: String,
    credentials: WakeCredentials,
    identities: List<String>,
    topics: List<String>,
): String {
    val canonical = listOf(
        gatewayUrl,
        pushToken,
        credentials.installId,
        credentials.encryptionPublicKey,
        credentials.deviceSecretSha256,
        identities.sorted().joinToString(","),
        topics.sorted().joinToString(","),
    ).joinToString("\n")
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).toHex()
}
