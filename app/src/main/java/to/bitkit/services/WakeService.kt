package to.bitkit.services

import com.synonym.bitkitcore.WakeAckOutcome
import com.synonym.bitkitcore.WakeDevice
import com.synonym.bitkitcore.WakeEnvelope
import com.synonym.bitkitcore.WakeEnvironment
import com.synonym.bitkitcore.WakeException
import com.synonym.bitkitcore.WakeIdentityProof
import com.synonym.bitkitcore.WakePlatform
import com.synonym.bitkitcore.WakeRegistration
import com.synonym.bitkitcore.WakeRegistrationRequest
import com.synonym.bitkitcore.WakeServerInfo
import com.synonym.bitkitcore.WakeTopic
import com.synonym.bitkitcore.wakeAck
import com.synonym.bitkitcore.wakeClearPresence
import com.synonym.bitkitcore.wakeDecryptPush
import com.synonym.bitkitcore.wakeGenerateDeviceSecret
import com.synonym.bitkitcore.wakeGenerateInstallId
import com.synonym.bitkitcore.wakeGenerateKeypair
import com.synonym.bitkitcore.wakeGetDevice
import com.synonym.bitkitcore.wakeListTopics
import com.synonym.bitkitcore.wakePrepareRegistration
import com.synonym.bitkitcore.wakeRegister
import com.synonym.bitkitcore.wakeSendTest
import com.synonym.bitkitcore.wakeServerInfo
import com.synonym.bitkitcore.wakeSetPresence
import com.synonym.bitkitcore.wakeUnregister
import kotlinx.serialization.Serializable
import to.bitkit.async.ServiceQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the wake gateway client of bitkit-core on [ServiceQueue.CORE]. A failure arrives wrapped by the queue, with the
 * core [WakeException] in its cause chain; [wakeRejection] finds a gateway rejection there.
 */
@Singleton
class WakeService @Inject constructor() {
    companion object {
        /** The app id the gateway serves Bitkit under. */
        const val APP = "bitkit"

        /** Code of the 401 a registration gets when its timestamp is too far from the gateway's clock. */
        const val STALE_TIMESTAMP = "stale_timestamp"
    }

    suspend fun generateCredentials(): WakeCredentials = ServiceQueue.CORE.background {
        val keyPair = wakeGenerateKeypair()
        val deviceSecret = wakeGenerateDeviceSecret()
        WakeCredentials(
            installId = wakeGenerateInstallId(),
            encryptionSecretKey = keyPair.secretKeyHex,
            encryptionPublicKey = keyPair.publicKeyHex,
            deviceSecret = deviceSecret.token,
            deviceSecretSha256 = deviceSecret.sha256Hex,
        )
    }

    suspend fun serverInfo(gatewayUrl: String): WakeServerInfo = ServiceQueue.CORE.background {
        wakeServerInfo(gatewayUrl = gatewayUrl)
    }

    suspend fun prepareRegistration(
        audience: String,
        credentials: WakeCredentials,
        pushToken: String,
        identities: List<String>,
        topics: List<String>,
        timestampSecs: Long,
    ): WakeRegistrationRequest = ServiceQueue.CORE.background {
        wakePrepareRegistration(
            audience = audience,
            app = APP,
            installId = credentials.installId,
            platform = WakePlatform.FCM,
            environment = WakeEnvironment.PRODUCTION,
            pushToken = pushToken,
            encryptionPublicKey = credentials.encryptionPublicKey,
            secretSha256 = credentials.deviceSecretSha256,
            identities = identities,
            topics = topics,
            timestamp = timestampSecs.toULong(),
        )
    }

    suspend fun register(
        gatewayUrl: String,
        request: WakeRegistrationRequest,
        proofs: List<WakeIdentityProof>,
    ): WakeRegistration = ServiceQueue.CORE.background {
        wakeRegister(gatewayUrl = gatewayUrl, request = request, proofs = proofs)
    }

    suspend fun decryptPush(secretKeyHex: String, pushJson: String): WakeEnvelope = ServiceQueue.CORE.background {
        wakeDecryptPush(secretKeyHex = secretKeyHex, pushJson = pushJson)
    }

    suspend fun ack(
        gatewayUrl: String,
        deviceSecret: String,
        wakeId: String,
        outcome: WakeAckOutcome,
    ) = ServiceQueue.CORE.background {
        wakeAck(gatewayUrl = gatewayUrl, deviceSecret = deviceSecret, wakeId = wakeId, outcome = outcome)
    }

    suspend fun setPresence(gatewayUrl: String, deviceSecret: String, ttlSecs: Int): Long =
        ServiceQueue.CORE.background {
            wakeSetPresence(gatewayUrl = gatewayUrl, deviceSecret = deviceSecret, ttlSecs = ttlSecs.toUInt()).toLong()
        }

    suspend fun clearPresence(gatewayUrl: String, deviceSecret: String) = ServiceQueue.CORE.background {
        wakeClearPresence(gatewayUrl = gatewayUrl, deviceSecret = deviceSecret)
    }

    suspend fun getDevice(gatewayUrl: String, deviceSecret: String): WakeDevice = ServiceQueue.CORE.background {
        wakeGetDevice(gatewayUrl = gatewayUrl, deviceSecret = deviceSecret)
    }

    suspend fun sendTest(gatewayUrl: String, deviceSecret: String): String = ServiceQueue.CORE.background {
        wakeSendTest(gatewayUrl = gatewayUrl, deviceSecret = deviceSecret)
    }

    suspend fun unregister(gatewayUrl: String, deviceSecret: String) = ServiceQueue.CORE.background {
        wakeUnregister(gatewayUrl = gatewayUrl, deviceSecret = deviceSecret)
    }

    suspend fun listTopics(gatewayUrl: String): List<WakeTopic> = ServiceQueue.CORE.background {
        wakeListTopics(gatewayUrl = gatewayUrl)
    }
}

/**
 * The keys and ids this install registers with. They belong to the install, so they are kept in the Keychain, reused
 * across launches and never backed up. [toString] leaves the secrets out, so logging one by mistake leaks nothing.
 */
@Serializable
data class WakeCredentials(
    val installId: String,
    val encryptionSecretKey: String,
    val encryptionPublicKey: String,
    val deviceSecret: String,
    val deviceSecretSha256: String,
) {
    override fun toString(): String = "WakeCredentials(installId='$installId')"
}

/** The gateway rejection in this failure's cause chain, if it has one. */
fun Throwable.wakeRejection(): WakeException.GatewayRejected? =
    generateSequence(this) { it.cause }.filterIsInstance<WakeException.GatewayRejected>().firstOrNull()
