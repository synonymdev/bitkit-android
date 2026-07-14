package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.lightningdevkit.ldknode.AddressType
import to.bitkit.async.ServiceQueue
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.fromHex
import to.bitkit.models.PreparedWatchOnlyAccountClaim
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.LightningService
import to.bitkit.utils.AppError
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class WatchOnlyAccountError(message: String) : AppError(message) {
    data object InvalidAccountName : WatchOnlyAccountError("Enter an account name between 1 and 64 characters.")
    data object InvalidAuthRequest : WatchOnlyAccountError("This authorization request is missing a valid secret.")
    data object InvalidExtendedPublicKey : WatchOnlyAccountError("Bitkit could not create a valid account xpub.")
    data object NodeUnavailable : WatchOnlyAccountError("The wallet must be running before an account can be created.")
    data object CompanionTransportUnavailable : WatchOnlyAccountError(
        "This request needs the signed watch-only claim transport from the Paykit SDK before it can be authorized."
    )
}

@Singleton
class WatchOnlyAccountClaimTransport @Inject constructor() {
    suspend fun deliver(
        @Suppress("UNUSED_PARAMETER") payload: ByteArray,
        @Suppress("UNUSED_PARAMETER") authUrl: String,
    ) {
        throw WatchOnlyAccountError.CompanionTransportUnavailable
    }
}

@Singleton
class WatchOnlyAccountRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val store: WatchOnlyAccountStore,
    private val lightningService: LightningService,
    private val keychain: Keychain,
    private val transport: WatchOnlyAccountClaimTransport,
) {
    private val mutationMutex = Mutex()

    val accounts: Flow<List<WatchOnlyAccountRecord>> = store.data.map { it.accounts }

    suspend fun prepareSignedClaim(authUrl: String, name: String): PreparedWatchOnlyAccountClaim =
        withContext(bgDispatcher) {
            mutationMutex.withLock {
                val normalizedName = normalizeName(name)
                val walletIndex = lightningService.currentWalletIndex
                val fingerprint = sha256(authUrl.encodeToByteArray()).toBase64()
                val current = store.load()
                current.firstOrNull {
                    it.walletIndex == walletIndex && it.requestFingerprint == fingerprint
                }?.let { existing ->
                    val refreshed = existing.copy(name = normalizedName)
                    if (refreshed != existing) {
                        store.save(current.map { if (it.id == existing.id) refreshed else it })
                    }
                    return@withLock PreparedWatchOnlyAccountClaim(
                        account = refreshed,
                        payload = WatchOnlyAccountClaimCodec.encode(refreshed, authUrl, localSecretKey()),
                    )
                }

                val highestAccountIndex = current
                    .filter { it.walletIndex == walletIndex }
                    .maxOfOrNull(WatchOnlyAccountRecord::accountIndex) ?: 0
                check(highestAccountIndex < Int.MAX_VALUE) { "Watch-only account index overflow" }
                val accountIndex = highestAccountIndex + 1
                val xpub = createAndTrackAccount(accountIndex)
                val account = WatchOnlyAccountRecord(
                    id = UUID.randomUUID().toString(),
                    walletIndex = walletIndex,
                    accountIndex = accountIndex,
                    addressType = ADDRESS_TYPE_NATIVE_SEGWIT,
                    xpub = xpub,
                    requestFingerprint = fingerprint,
                    createdAt = System.currentTimeMillis(),
                    name = normalizedName,
                    isTrackingEnabled = true,
                    setupState = WatchOnlyAccountSetupState.PendingDelivery,
                )
                store.save(current + account)
                PreparedWatchOnlyAccountClaim(
                    account = account,
                    payload = WatchOnlyAccountClaimCodec.encode(account, authUrl, localSecretKey()),
                )
            }
        }

    suspend fun deliver(prepared: PreparedWatchOnlyAccountClaim, authUrl: String) {
        transport.deliver(prepared.payload, authUrl)
    }

    suspend fun markActive(id: String) = updateAccount(id) { it.copy(setupState = WatchOnlyAccountSetupState.Active) }

    suspend fun rename(id: String, name: String) {
        val normalizedName = normalizeName(name)
        updateAccount(id) { it.copy(name = normalizedName) }
    }

    suspend fun setTrackingEnabled(id: String, enabled: Boolean) =
        updateAccount(id) { it.copy(isTrackingEnabled = enabled) }

    suspend fun restore(accounts: List<WatchOnlyAccountRecord>?) {
        store.save(accounts.orEmpty())
    }

    private suspend fun createAndTrackAccount(accountIndex: Int): String = ServiceQueue.LDK.background {
        val node = lightningService.node ?: throw WatchOnlyAccountError.NodeUnavailable
        val index = accountIndex.toUInt()
        val xpub = node.exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, index)
        if (node.listOnchainWalletAccounts().none {
                it.addressType == AddressType.NATIVE_SEGWIT && it.accountIndex == index
            }
        ) {
            node.addOnchainWalletAccount(AddressType.NATIVE_SEGWIT, index, xpub)
        }
        node.syncWallets()
        xpub
    }

    private suspend fun updateAccount(id: String, transform: (WatchOnlyAccountRecord) -> WatchOnlyAccountRecord) {
        mutationMutex.withLock {
            store.update { accounts -> accounts.map { if (it.id == id) transform(it) else it } }
        }
    }

    private fun localSecretKey(): ByteArray = requireNotNull(
        keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)?.takeIf(String::isNotBlank)
    ) { "No local Pubky identity is available" }.fromHex()

    private fun normalizeName(name: String): String {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_NAME_LENGTH) {
            throw WatchOnlyAccountError.InvalidAccountName
        }
        return normalized
    }

    companion object {
        const val ADDRESS_TYPE_NATIVE_SEGWIT = "nativeSegwit"
        private const val MAX_NAME_LENGTH = 64
    }
}

object WatchOnlyAccountClaimCodec {
    const val VERSION: Byte = 1
    const val NATIVE_SEGWIT_ADDRESS_TYPE: Byte = 0
    const val SERIALIZED_XPUB_LENGTH = 78
    const val PAYLOAD_LENGTH = 1 + 4 + 1 + SERIALIZED_XPUB_LENGTH + 64

    private val signatureDomain = "x-bitkit-claim|watch-only-account-v1|".encodeToByteArray()
    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val BASE58_RADIX = 58L
    private const val BASE58_CHECKSUM_LENGTH = 4

    fun encode(account: WatchOnlyAccountRecord, authUrl: String, secretKey: ByteArray): ByteArray {
        if (account.addressType != WatchOnlyAccountRepo.ADDRESS_TYPE_NATIVE_SEGWIT || secretKey.size != 32) {
            throw WatchOnlyAccountError.InvalidExtendedPublicKey
        }
        val rawXpub = decodeBase58Check(account.xpub)
        if (rawXpub.size != SERIALIZED_XPUB_LENGTH) throw WatchOnlyAccountError.InvalidExtendedPublicKey

        val unsignedClaim = ByteBuffer.allocate(1 + 4 + 1 + SERIALIZED_XPUB_LENGTH)
            .put(VERSION)
            .putInt(account.accountIndex)
            .put(NATIVE_SEGWIT_ADDRESS_TYPE)
            .put(rawXpub)
            .array()
        val signable = signatureDomain + requestSecretHash(authUrl) + unsignedClaim
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(secretKey, 0))
            update(signable, 0, signable.size)
        }
        return unsignedClaim + signer.generateSignature()
    }

    fun requestSecretHash(authUrl: String): ByteArray {
        val rawQuery = runCatching { URI(authUrl).rawQuery }.getOrNull()
            ?: throw WatchOnlyAccountError.InvalidAuthRequest
        val secrets = rawQuery.split('&').mapNotNull { item ->
            val parts = item.split('=', limit = 2)
            if (percentDecode(parts[0]) != "secret") return@mapNotNull null
            percentDecode(parts.getOrElse(1) { "" })
        }
        if (secrets.size != 1 || secrets.first().isEmpty()) throw WatchOnlyAccountError.InvalidAuthRequest
        return sha256(secrets.first().encodeToByteArray())
    }

    private fun decodeBase58Check(value: String): ByteArray {
        if (value.isEmpty()) invalidExtendedPublicKey()
        var number = BigInteger.ZERO
        value.forEach { character ->
            val digit = BASE58_ALPHABET.indexOf(character)
            if (digit < 0) invalidExtendedPublicKey()
            number = number.multiply(BigInteger.valueOf(BASE58_RADIX)).add(BigInteger.valueOf(digit.toLong()))
        }
        val magnitude = number.toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.drop(1).toByteArray() else it
        }
        val decoded = ByteArray(value.takeWhile { it == '1' }.length) + magnitude
        if (decoded.size <= BASE58_CHECKSUM_LENGTH) invalidExtendedPublicKey()
        val payload = decoded.copyOfRange(0, decoded.size - BASE58_CHECKSUM_LENGTH)
        val checksum = decoded.copyOfRange(decoded.size - BASE58_CHECKSUM_LENGTH, decoded.size)
        val expected = sha256(sha256(payload)).copyOf(BASE58_CHECKSUM_LENGTH)
        if (!MessageDigest.isEqual(checksum, expected)) invalidExtendedPublicKey()
        return payload
    }

    private fun invalidExtendedPublicKey(): Nothing = throw WatchOnlyAccountError.InvalidExtendedPublicKey

    private fun percentDecode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
