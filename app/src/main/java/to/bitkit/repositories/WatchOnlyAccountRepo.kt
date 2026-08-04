package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.AddressType
import to.bitkit.async.ServiceQueue
import to.bitkit.data.WatchOnlyAccountAllocationState
import to.bitkit.data.WatchOnlyAccountStore
import to.bitkit.data.WatchOnlyAccountXpubSerializer
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.nowMillis
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PreparedWatchOnlyAccountClaim
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX
import to.bitkit.models.WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE
import to.bitkit.models.WATCH_ONLY_ACCOUNT_SERIALIZED_XPUB_LENGTH
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.services.LightningService
import to.bitkit.services.WatchOnlyAccountLifecycleCoordinator
import to.bitkit.utils.AppError
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ExperimentalTime

sealed class WatchOnlyAccountError : AppError() {
    class AuthorizationAccountMissing : WatchOnlyAccountError()
    class InvalidAccountName : WatchOnlyAccountError()
    class InvalidExtendedPublicKey : WatchOnlyAccountError()
    class NodeUnavailable : WatchOnlyAccountError()
}

class WatchOnlyAccountAuthorizationStartError(
    val preserveAuthorizingState: Boolean,
    cause: Throwable,
) : AppError(cause.message, cause.cause ?: cause)

@Singleton
@OptIn(ExperimentalTime::class)
class WatchOnlyAccountRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val store: WatchOnlyAccountStore,
    private val lightningService: LightningService,
    private val lifecycleCoordinator: WatchOnlyAccountLifecycleCoordinator,
    private val xpubSerializer: WatchOnlyAccountXpubSerializer,
) {
    val accounts: Flow<List<WatchOnlyAccountRecord>> = store.data.map { it.accounts }
    val currentWalletAccounts: Flow<List<WatchOnlyAccountRecord>> = accounts.map { accounts ->
        accounts.filter { it.walletIndex == lightningService.currentWalletIndex }
    }
    val currentWalletAccountCount: Flow<Int> = currentWalletAccounts.map { it.size }

    suspend fun prepareUnsignedClaim(authUrl: String, name: String): PreparedWatchOnlyAccountClaim =
        withContext(bgDispatcher) {
            lifecycleCoordinator.withLock {
                val normalizedName = normalizeName(name)
                val walletIndex = lightningService.currentWalletIndex
                val fingerprint = requestFingerprint(authUrl)
                val current = store.load()
                current.firstOrNull {
                    it.walletIndex == walletIndex &&
                        it.requestFingerprint == fingerprint &&
                        it.setupState != WatchOnlyAccountSetupState.Active
                }?.let { existing ->
                    val refreshed = existing.copy(name = normalizedName)
                    if (refreshed != existing) {
                        store.save(current.map { if (it.id == existing.id) refreshed else it })
                    }
                    return@withLock PreparedWatchOnlyAccountClaim(
                        account = refreshed,
                        payload = WatchOnlyAccountClaimCodec.encode(refreshed, xpubSerializer::serialize),
                    )
                }

                val accountIndex = store.reserveAccountIndex(walletIndex, fingerprint)
                val xpub = exportAccountXpub(accountIndex)
                val account = WatchOnlyAccountRecord(
                    id = UUID.randomUUID().toString(),
                    walletIndex = walletIndex,
                    accountIndex = accountIndex,
                    addressType = WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE,
                    xpub = xpub,
                    requestFingerprint = fingerprint,
                    createdAt = nowMillis(),
                    name = normalizedName,
                    isTrackingEnabled = false,
                    setupState = WatchOnlyAccountSetupState.PendingDelivery,
                )
                store.save(current + account)
                PreparedWatchOnlyAccountClaim(
                    account = account,
                    payload = WatchOnlyAccountClaimCodec.encode(account, xpubSerializer::serialize),
                )
            }
        }

    suspend fun markActive(id: String) = withContext(NonCancellable) {
        lifecycleCoordinator.withLock {
            if (store.load().none { it.id == id }) {
                throw WatchOnlyAccountError.AuthorizationAccountMissing()
            }
            store.markActive(id)
        }
    }

    suspend fun beginAuthorization(id: String) = withContext(NonCancellable) {
        lifecycleCoordinator.withLock {
            val account = store.load().firstOrNull {
                it.id == id && it.setupState != WatchOnlyAccountSetupState.Active
            } ?: throw WatchOnlyAccountError.AuthorizationAccountMissing()
            val preserveAuthorizingState = account.setupState == WatchOnlyAccountSetupState.Authorizing
            runSuspendCatching {
                setAccountTracking(account, enabled = true)
                val updateResult = runSuspendCatching {
                    store.update { accounts ->
                        accounts.map {
                            if (it.id == id) {
                                it.copy(
                                    isTrackingEnabled = true,
                                    setupState = WatchOnlyAccountSetupState.Authorizing,
                                )
                            } else {
                                it
                            }
                        }
                    }
                }
                updateResult.onFailure {
                    runSuspendCatching { setAccountTracking(account, enabled = account.isTrackingEnabled) }
                }
                updateResult.getOrThrow()
            }.getOrElse {
                throw WatchOnlyAccountAuthorizationStartError(preserveAuthorizingState, it)
            }
            preserveAuthorizingState
        }
    }

    suspend fun cancelAuthorization(
        id: String,
        preserveAuthorizingState: Boolean = false,
    ) = withContext(NonCancellable) {
        lifecycleCoordinator.withLock {
            val current = store.load()
            val account = current.firstOrNull {
                it.id == id && it.setupState != WatchOnlyAccountSetupState.Active
            } ?: return@withLock
            val shouldPreserveAuthorizingState = preserveAuthorizingState &&
                account.setupState == WatchOnlyAccountSetupState.Authorizing
            setAccountTracking(account, enabled = shouldPreserveAuthorizingState)
            val saveResult = runSuspendCatching {
                store.save(
                    current.map {
                        if (it.id == id) {
                            it.copy(
                                isTrackingEnabled = shouldPreserveAuthorizingState,
                                setupState = if (shouldPreserveAuthorizingState) {
                                    WatchOnlyAccountSetupState.Authorizing
                                } else {
                                    WatchOnlyAccountSetupState.PendingDelivery
                                },
                            )
                        } else {
                            it
                        }
                    },
                )
            }
            saveResult.onFailure {
                runSuspendCatching {
                    setAccountTracking(account, enabled = account.isTrackingEnabled)
                }
            }
            saveResult.getOrThrow()
        }
    }

    suspend fun rename(id: String, name: String) {
        val normalizedName = normalizeName(name)
        updateAccount(id) { it.copy(name = normalizedName) }
    }

    suspend fun setTrackingEnabled(id: String, enabled: Boolean) = withContext(NonCancellable) {
        lifecycleCoordinator.withLock {
            val current = store.load()
            val account = current.firstOrNull { it.id == id } ?: return@withLock
            if (account.setupState != WatchOnlyAccountSetupState.Active) return@withLock
            if (account.isTrackingEnabled == enabled) return@withLock

            setAccountTracking(account, enabled)
            val saveResult = runSuspendCatching {
                store.save(current.map { if (it.id == id) it.copy(isTrackingEnabled = enabled) else it })
            }
            saveResult.onFailure {
                runSuspendCatching { setAccountTracking(account, enabled = !enabled) }
            }
            saveResult.getOrThrow()
        }
    }

    suspend fun restore(
        accounts: List<WatchOnlyAccountRecord>?,
        allocationState: WatchOnlyAccountAllocationState? = null,
    ) {
        lifecycleCoordinator.withLock {
            store.restore(accounts.orEmpty(), allocationState)
        }
    }

    suspend fun clear() {
        lifecycleCoordinator.withLock {
            store.clear()
        }
    }

    private suspend fun exportAccountXpub(accountIndex: Int): String = ServiceQueue.LDK.background {
        val node = lightningService.node ?: throw WatchOnlyAccountError.NodeUnavailable()
        node.exportOnchainWalletAccountXpub(AddressType.NATIVE_SEGWIT, accountIndex.toUInt())
    }

    private suspend fun setAccountTracking(
        account: WatchOnlyAccountRecord,
        enabled: Boolean,
    ) = ServiceQueue.LDK.background {
        val node = lightningService.node ?: throw WatchOnlyAccountError.NodeUnavailable()
        val addressType = when (account.addressType) {
            WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE -> AddressType.NATIVE_SEGWIT
            else -> throw WatchOnlyAccountError.InvalidExtendedPublicKey()
        }
        val accountIndex = account.accountIndex.toUInt()
        val isTracked = node.listOnchainWalletAccounts().any {
            it.addressType == addressType && it.accountIndex == accountIndex
        }

        when {
            enabled -> {
                val wasAdded = !isTracked
                if (wasAdded) {
                    node.addOnchainWalletAccount(addressType, accountIndex, account.xpub)
                }
                val trackingResult = runSuspendCatching {
                    node.onchainPayment().revealReceiveAddressesToAccount(
                        addressType,
                        accountIndex,
                        WATCH_ONLY_ACCOUNT_HIGHEST_PRE_REVEALED_ADDRESS_INDEX.toUInt(),
                    )
                    if (wasAdded) {
                        node.syncWallets()
                    }
                }
                trackingResult.onFailure {
                    if (wasAdded) {
                        runSuspendCatching {
                            node.removeOnchainWalletAccount(addressType, accountIndex)
                        }
                    }
                }
                trackingResult.getOrThrow()
            }
            !enabled && isTracked -> node.removeOnchainWalletAccount(addressType, accountIndex)
        }
    }

    private suspend fun updateAccount(id: String, transform: (WatchOnlyAccountRecord) -> WatchOnlyAccountRecord) {
        lifecycleCoordinator.withLock {
            store.update { accounts -> accounts.map { if (it.id == id) transform(it) else it } }
        }
    }

    private fun normalizeName(name: String): String {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > MAX_NAME_LENGTH) {
            throw WatchOnlyAccountError.InvalidAccountName()
        }
        return normalized
    }

    private fun requestFingerprint(authUrl: String): String {
        val fingerprintSource = runCatching {
            val uri = URI(authUrl)
            val queryValues = uri.rawQuery.orEmpty()
                .split("&")
                .filter(String::isNotEmpty)
                .map { it.split("=", limit = 2) }
                .groupBy(
                    keySelector = { decodeQueryComponent(it.first()) },
                    valueTransform = { decodeQueryComponent(it.getOrElse(1) { "" }) },
                )
            val relay = queryValues.singleValue("relay")
            val secret = queryValues.singleValue("secret")
            val capabilities = queryValues.singleValue("caps")
            val claim = queryValues.singleValue(PubkyAuthClaim.QUERY_PARAMETER)
            listOf(
                requireNotNull(uri.scheme).lowercase(),
                requireNotNull(uri.host).lowercase(),
                uri.path.orEmpty(),
                relay,
                secret,
                capabilities,
                claim,
            ).joinToString("\u0000")
        }.getOrDefault(authUrl)
        return sha256(fingerprintSource.encodeToByteArray()).toBase64()
    }

    companion object {
        private const val MAX_NAME_LENGTH = 64
    }
}

private fun Map<String, List<String>>.singleValue(name: String): String {
    val values = getValue(name)
    return values.single().also { require(it.isNotEmpty()) }
}

private fun decodeQueryComponent(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())

object WatchOnlyAccountClaimCodec {
    const val VERSION: Byte = 1
    const val NATIVE_SEGWIT_ADDRESS_TYPE: Byte = 0
    const val SERIALIZED_XPUB_LENGTH = WATCH_ONLY_ACCOUNT_SERIALIZED_XPUB_LENGTH
    const val PAYLOAD_LENGTH = 1 + 4 + 1 + SERIALIZED_XPUB_LENGTH

    fun encode(
        account: WatchOnlyAccountRecord,
        serializeXpub: (String) -> ByteArray,
    ): ByteArray {
        val rawXpub = if (account.addressType == WATCH_ONLY_ACCOUNT_NATIVE_SEGWIT_ADDRESS_TYPE) {
            runCatching { serializeXpub(account.xpub) }.getOrNull()
        } else {
            null
        }
        if (rawXpub?.size != SERIALIZED_XPUB_LENGTH) throw WatchOnlyAccountError.InvalidExtendedPublicKey()

        return ByteBuffer.allocate(PAYLOAD_LENGTH)
            .put(VERSION)
            .putInt(account.accountIndex)
            .put(NATIVE_SEGWIT_ADDRESS_TYPE)
            .put(rawXpub)
            .array()
    }
}

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
