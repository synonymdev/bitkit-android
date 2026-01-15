package to.bitkit.services

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.deriveNodeSecretFromMnemonic
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.toHex
import to.bitkit.utils.AppError
import to.bitkit.utils.Crypto
import to.bitkit.utils.Logger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RNBackupClient @Inject constructor(
    private val httpClient: HttpClient,
    private val crypto: Crypto,
    private val keychain: Keychain,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val json: Json,
) {
    @Suppress("SpellCheckingInspection")
    companion object {
        private const val TAG = "RNBackup"
        private const val VERSION = "v1"
        private const val SIGNED_MESSAGE_PREFIX = "react-native-ldk backup server auth:"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
    }

    @Volatile
    private var cachedBearer: AuthBearerResponse? = null

    private val authMutex = Mutex()

    suspend fun listFiles(fileGroup: String? = "ldk"): RNBackupListResponse? = withContext(ioDispatcher) {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw RNBackupError.NotSetup()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            val bearer = authenticate(mnemonic, passphrase)
            val url = buildUrl("list", fileGroup = fileGroup, network = getNetworkString())
            val response: HttpResponse = httpClient.get(url) {
                header("Authorization", bearer.bearer)
            }

            if (!response.status.isSuccess()) throw RNBackupError.RequestFailed("Status: ${response.status.value}")

            response.body<RNBackupListResponse>()
        }.onFailure { e ->
            Logger.error("Failed to list files for fileGroup=$fileGroup", e, context = TAG)
        }.getOrNull()
    }

    suspend fun retrieve(label: String, fileGroup: String? = null): ByteArray? = withContext(ioDispatcher) {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw RNBackupError.NotSetup()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            val bearer = authenticate(mnemonic, passphrase)
            val url = buildUrl("retrieve", label = label, fileGroup = fileGroup, network = getNetworkString())
            val response: HttpResponse = httpClient.get(url) {
                header("Authorization", bearer.bearer)
            }

            if (!response.status.isSuccess()) throw RNBackupError.RequestFailed("Status: ${response.status.value}")

            val encryptedData = response.body<ByteArray>()
            if (encryptedData.isEmpty()) throw RNBackupError.RequestFailed("Retrieved data is empty")

            val encryptionKey = deriveEncryptionKey(mnemonic, passphrase)

            decrypt(encryptedData, encryptionKey).also {
                if (it.isEmpty()) throw RNBackupError.DecryptFailed("Decrypted data is empty")
            }
        }.onFailure { e ->
            Logger.error("Failed to retrieve $label", e, context = TAG)
        }.getOrNull()
    }

    suspend fun retrieveChannelMonitor(channelId: String): ByteArray? = withContext(ioDispatcher) {
        runCatching {
            val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name) ?: throw RNBackupError.NotSetup()
            val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)

            val bearer = authenticate(mnemonic, passphrase)
            val url = buildUrl(
                method = "retrieve",
                label = "channel_monitor",
                fileGroup = "ldk",
                channelId = channelId,
                network = getNetworkString(),
            )
            val response: HttpResponse = httpClient.get(url) {
                header("Authorization", bearer.bearer)
            }

            if (!response.status.isSuccess()) throw RNBackupError.RequestFailed("Status: ${response.status.value}")

            val encryptedData = response.body<ByteArray>()
            if (encryptedData.isEmpty()) throw RNBackupError.RequestFailed("Retrieved data is empty")

            val encryptionKey = deriveEncryptionKey(mnemonic, passphrase)
            decrypt(encryptedData, encryptionKey).also {
                if (it.isEmpty()) throw RNBackupError.DecryptFailed("Decrypted data is empty")
            }
        }.onFailure { e ->
            Logger.error("Failed to retrieve channel monitor $channelId", e, context = TAG)
        }.getOrNull()
    }

    suspend fun getLatestBackupTimestamp(): ULong? = withContext(ioDispatcher) {
        runCatching {
            val bitkitFiles = listFiles(fileGroup = "bitkit")?.list ?: return@withContext null
            if (bitkitFiles.isEmpty()) return@withContext null

            @Serializable
            data class BackupMetadata(val timestamp: Long? = null)

            @Serializable
            data class BackupWithMetadata(val metadata: BackupMetadata? = null)

            val labels = listOf(
                "bitkit_settings",
                "bitkit_metadata",
                "bitkit_widgets",
                "bitkit_lightning_activity",
                "bitkit_wallet",
                "bitkit_blocktank_orders",
            )

            var latestTimestamp: ULong? = null
            for (label in labels) {
                if ("$label.bin" in bitkitFiles) {
                    retrieve(label, fileGroup = "bitkit")?.let { data ->
                        runCatching {
                            json.decodeFromString<BackupWithMetadata>(String(data)).metadata?.timestamp
                        }.getOrNull()?.let { timestamp ->
                            val ts = (timestamp / 1000).toULong()
                            latestTimestamp = maxOf(latestTimestamp ?: 0uL, ts)
                        }
                    }
                }
            }
            latestTimestamp
        }.onFailure { e ->
            Logger.error("Failed to get latest backup timestamp", e, context = TAG)
        }.getOrNull()
    }

    private fun buildUrl(
        method: String,
        label: String? = null,
        fileGroup: String? = null,
        channelId: String? = null,
        network: String,
    ): String {
        var url = "${Env.rnBackupServerHost}/$VERSION/$method?network=$network"
        label?.let { url += "&label=$it" }
        fileGroup?.let { url += "&fileGroup=$it" }
        channelId?.let { url += "&channelId=$it" }
        return url
    }

    private suspend fun authenticate(mnemonic: String, passphrase: String?): AuthBearerResponse {
        fun isBearerValid(bearer: AuthBearerResponse): Boolean {
            val now = System.currentTimeMillis() / 1000.0
            return bearer.expires / 1000.0 > now
        }

        cachedBearer?.takeIf { isBearerValid(it) }?.let { return it }

        return authMutex.withLock {
            cachedBearer?.takeIf { isBearerValid(it) }?.let { return@withLock it }
            cachedBearer = null

            val secretKey = deriveSigningKey(mnemonic, passphrase)
            val pubKeyHex = crypto.getPublicKey(secretKey).toHex()
            val networkString = getNetworkString()
            val timestamp = System.currentTimeMillis() / 1000

            val challengeBody = json.encodeToString(
                buildJsonObject {
                    put("timestamp", timestamp.toString())
                    put("signature", signMessage(timestamp.toString(), secretKey))
                }
            )
            val challengeResponse: HttpResponse = httpClient.post(
                "${Env.rnBackupServerHost}/$VERSION/auth/challenge?network=$networkString"
            ) {
                header("Public-Key", pubKeyHex)
                header("Content-Type", "application/json")
                setBody(challengeBody)
            }

            if (!challengeResponse.status.isSuccess()) throw RNBackupError.AuthFailed()

            val challengeResult = challengeResponse.body<AuthChallengeResponse>()
            val authBody = json.encodeToString(
                buildJsonObject {
                    put("signature", signMessage(challengeResult.challenge, secretKey))
                }
            )
            val authResponse: HttpResponse = httpClient.post(
                "${Env.rnBackupServerHost}/$VERSION/auth/response?network=$networkString"
            ) {
                header("Public-Key", pubKeyHex)
                header("Content-Type", "application/json")
                setBody(authBody)
            }

            if (!authResponse.status.isSuccess()) throw RNBackupError.AuthFailed()

            authResponse.body<AuthBearerResponse>().also { cachedBearer = it }
        }
    }

    private fun getNetworkString(): String {
        return when (Env.network) {
            Network.BITCOIN -> "bitcoin"
            Network.TESTNET -> "testnet"
            Network.REGTEST -> "regtest"
            Network.SIGNET -> "signet"
        }
    }

    private fun signMessage(message: String, privateKey: ByteArray): String {
        val fullMessage = "$SIGNED_MESSAGE_PREFIX$message"
        return crypto.sign(fullMessage, privateKey)
    }

    private fun deriveSigningKey(mnemonic: String, passphrase: String?): ByteArray =
        deriveNodeSecretFromMnemonic(mnemonic, passphrase).map { it.toByte() }.toByteArray()

    private fun deriveEncryptionKey(mnemonic: String, passphrase: String?): ByteArray =
        deriveSigningKey(mnemonic, passphrase)

    private fun decrypt(blob: ByteArray, encryptionKey: ByteArray): ByteArray {
        if (blob.size < GCM_IV_LENGTH + GCM_TAG_LENGTH) throw RNBackupError.DecryptFailed("Data too short")

        val nonce = blob.sliceArray(0 until GCM_IV_LENGTH)
        val tag = blob.sliceArray(blob.size - GCM_TAG_LENGTH until blob.size)
        val ciphertext = blob.sliceArray(GCM_IV_LENGTH until blob.size - GCM_TAG_LENGTH)

        val key = SecretKeySpec(encryptionKey, "AES")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, spec)
        }

        return cipher.doFinal(ciphertext + tag)
    }
}

@Serializable
private data class AuthChallengeResponse(
    val challenge: String,
)

@Serializable
private data class AuthBearerResponse(
    val bearer: String,
    val expires: Long,
)

@Serializable
data class RNBackupListResponse(
    val list: List<String> = emptyList(),
    @SerialName("channel_monitors") val channelMonitors: List<String> = emptyList(),
)

sealed class RNBackupError(message: String) : AppError(message) {
    class NotSetup : RNBackupError("RN backup client not setup")
    class AuthFailed : RNBackupError("Authentication failed")
    class RequestFailed(override val message: String) : RNBackupError(message)
    class DecryptFailed(override val message: String) : RNBackupError(message)
}
