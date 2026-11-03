package to.bitkit.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.hasPendingWalletRestore
import to.bitkit.env.Env
import to.bitkit.models.SettingsBackupV1
import to.bitkit.utils.AppError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Local connection choices made while recovering a wallet, never included in a remote backup. */
@Singleton
class WalletConnectionRecovery @Inject constructor(
    private val keychain: Keychain,
    private val db: AppDb,
    private val storeIdProvider: VssStoreIdProvider,
    private val settingsStore: SettingsStore,
) {
    companion object {
        internal const val KEY = "WALLET_CONNECTION_RECOVERY"
    }

    private val stateLock = Any()
    private val operationMutex = Mutex()
    private val startupMutex = Mutex()
    private val settingsMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private var generation = 0L
    private var wiping = false
    private val jobs = mutableSetOf<Job>()
    private val json = Json { encodeDefaults = true }

    class Owner internal constructor(val walletIndex: Int, internal val binding: String?, internal val generation: Long)

    suspend fun captureOwner(): Owner = captureOwner(bindToCredentials = true)

    internal suspend fun captureRuntimeOwner(): Owner = captureOwner(bindToCredentials = false)

    private suspend fun captureOwner(bindToCredentials: Boolean): Owner {
        val capturedGeneration = admittedGeneration()
        return lifecycleMutex.withLock {
            checkGeneration(capturedGeneration)
            val index = db.configDao().getAll().first().firstOrNull()?.walletIndex ?: 0L
            require(index in 0..Int.MAX_VALUE.toLong())
            val binding = if (bindToCredentials) storeIdProvider.getBackupWalletBinding(index.toInt()) else null
            Owner(index.toInt(), binding, capturedGeneration).also { checkOwner(it) }
        }
    }

    /** A queued UI intent remains bound to its original wallet, including same-seed replacement. */
    internal fun currentGeneration(): Long? = synchronized(stateLock) { generation.takeUnless { wiping } }

    internal fun isCurrentGeneration(expected: Long?): Boolean = synchronized(stateLock) {
        expected != null && !wiping && generation == expected
    }

    private fun admittedGeneration(): Long = synchronized(stateLock) {
        checkAdmission()
        generation
    }

    private fun checkGeneration(expected: Long) = synchronized(stateLock) {
        checkAdmission()
        if (generation != expected) throw CancellationException("Wallet recovery owner changed")
    }

    /** Callers pass this session to nested restore steps instead of reacquiring the operation mutex. */
    suspend fun <T> withOperation(owner: Owner, block: suspend (Session) -> T): T =
        withAdmission(owner, operationMutex, block)

    /** Startup must remain able to satisfy LDK-dependent restoration while a restore is admitted. */
    suspend fun <T> withStartup(owner: Owner, block: suspend (Session) -> T): T =
        withAdmission(owner, startupMutex, block)

    private suspend fun <T> withAdmission(
        owner: Owner,
        mutex: Mutex,
        block: suspend (Session) -> T,
    ): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(stateLock) {
            checkAdmission()
            if (owner.generation != generation) throw CancellationException("Wallet recovery owner changed")
            jobs.add(job)
        }
        try {
            mutex.withLock {
                checkOwner(owner)
                val session = Session(owner, job)
                try {
                    block(session)
                } finally {
                    session.active = false
                }
            }
        } finally {
            synchronized(stateLock) { jobs.remove(job) }
        }
    }

    /** Source selection must not add credential reads before the selected restore path arms its gate. */
    suspend fun <T> withCurrentWallet(block: suspend (Session) -> T): T =
        withOperation(captureRuntimeOwner(), block)

    /** Drains original owners before credentials, settings or the SDK wipe barrier can change. */
    suspend fun <T> withIdentityChange(
        isStillNeeded: suspend () -> Boolean = { true },
        block: suspend () -> T,
    ): T {
        val capturedGeneration = admittedGeneration()
        return lifecycleMutex.withLock {
            checkGeneration(capturedGeneration)
            if (!isStillNeeded()) throw CancellationException("Wallet identity transition no longer needed")
            try {
                currentCoroutineContext().ensureActive()
                beginWipe()
                currentCoroutineContext().ensureActive()
                block()
            } finally {
                storeIdProvider.clearCache()
                endWipe()
            }
        }
    }

    private suspend fun beginWipe() {
        val pending = synchronized(stateLock) {
            check(!wiping) { "Connection recovery wipe already active" }
            wiping = true
            generation += 1
            jobs.toList()
        }
        withContext(NonCancellable) {
            pending.forEach { it.cancel(CancellationException("Wallet wipe started")) }
            pending.joinAll()
        }
    }

    private fun endWipe() {
        synchronized(stateLock) { wiping = false }
    }

    inner class Session internal constructor(private val owner: Owner, private val job: Job) {
        @Volatile
        internal var active = true

        private suspend fun checkCurrent(checkCancellation: Boolean = true) {
            if (checkCancellation) job.ensureActive()
            check(active) { "Connection recovery operation ended" }
            checkOwner(owner)
        }

        /** Publish local UI state only while this original owner still holds admission. */
        internal suspend fun <T> commitCurrentState(block: () -> T): T {
            checkCurrent()
            return synchronized(stateLock) {
                job.ensureActive()
                check(active) { "Connection recovery operation ended" }
                checkAdmission()
                if (owner.generation != generation) throw CancellationException("Wallet recovery owner changed")
                block()
            }
        }

        private suspend fun read(): ConnectionChoices? {
            checkCurrent()
            val encoded = keychain.loadString(KEY, owner.walletIndex) ?: return null
            val record = json.decodeFromString<ConnectionChoices>(encoded)
            require(record.version == 1 && UUID.fromString(record.revision).toString() == record.revision)
            val binding = owner.binding ?: storeIdProvider.getBackupWalletBinding(owner.walletIndex)
            if (record.walletIndex != owner.walletIndex || record.binding != binding ||
                record.network != Env.network.name
            ) {
                return null
            }
            return record
        }

        /** A present RGS choice with null value means the platform's absent default, not no override. */
        suspend fun save(electrum: String? = null, rgs: RgsChoice? = null): SettingsData = settingsMutex.withLock {
            require(electrum != null || rgs != null)
            checkCurrent()
            val previous = read()
            val record = ConnectionChoices(
                walletIndex = owner.walletIndex,
                binding = checkNotNull(owner.binding) { "Connection edits require a credential-bound owner" },
                network = Env.network.name,
                revision = UUID.randomUUID().toString(),
                electrum = electrum ?: previous?.electrum,
                rgs = rgs ?: previous?.rgs,
            )
            // Once the durable intent starts, view cancellation cannot interrupt its settings write.
            // Wipe invalidates this owner, then drains the owning job before erasing either store.
            withContext(NonCancellable) {
                checkCurrent()
                keychain.upsertString(KEY, json.encodeToString(record), owner.walletIndex)
                checkCurrent(checkCancellation = false)
                settingsStore.update { record.applyTo(it) }
                checkCurrent(checkCancellation = false)
                settingsStore.data.first().also {
                    require(record.matches(it))
                    checkCurrent(checkCancellation = false)
                }
            }
        }

        suspend fun restoreSettings(restored: SettingsBackupV1) = settingsMutex.withLock {
            val choices = read()
            settingsStore.restoreFromBackup(
                restored.copy(settings = choices?.applyTo(restored.settings) ?: restored.settings),
            ).getOrThrow()
            checkCurrent()
        }

        /** Required even when the optional remote SETTINGS record is missing or failed to restore. */
        suspend fun replay(): String? = settingsMutex.withLock {
            val record = read() ?: return@withLock null
            settingsStore.update { record.applyTo(it) }
            checkCurrent()
            require(record.matches(settingsStore.data.first()))
            checkCurrent()
            record.revision
        }

        suspend fun pendingRevision(): String? = settingsMutex.withLock { read()?.revision }

        suspend fun complete(startedRevision: String?) = settingsMutex.withLock {
            checkCurrent()
            if (keychain.hasPendingWalletRestore()) return@withLock
            val record = read() ?: return@withLock
            if (record.revision != startedRevision) return@withLock
            require(record.matches(settingsStore.data.first()))
            checkCurrent()
            keychain.delete(KEY, owner.walletIndex)
        }
    }

    private fun checkAdmission() {
        if (wiping) throw CancellationException("Wallet wipe in progress")
    }

    private suspend fun checkOwner(owner: Owner) {
        synchronized(stateLock) {
            checkAdmission()
            if (owner.generation != generation) throw CancellationException("Wallet recovery owner changed")
        }
        val activeIndex = db.configDao().getAll().first().firstOrNull()?.walletIndex ?: 0L
        if (activeIndex != owner.walletIndex.toLong() ||
            (owner.binding != null && storeIdProvider.getBackupWalletBinding(owner.walletIndex) != owner.binding)
        ) {
            throw ConnectionRecoveryOwnerChanged()
        }
        synchronized(stateLock) {
            checkAdmission()
            if (owner.generation != generation) throw CancellationException("Wallet recovery owner changed")
        }
    }

    @Serializable
    data class RgsChoice(val value: String?)

    @Serializable
    private data class ConnectionChoices(
        val version: Int = 1,
        val walletIndex: Int,
        val binding: String,
        val network: String,
        val revision: String,
        val electrum: String? = null,
        val rgs: RgsChoice? = null,
    ) {
        fun applyTo(settings: SettingsData) = settings.copy(
            electrumServer = electrum ?: settings.electrumServer,
            rgsServerUrl = if (rgs != null) rgs.value else settings.rgsServerUrl,
        )

        fun matches(settings: SettingsData) =
            (electrum == null || settings.electrumServer == electrum) &&
                (rgs == null || settings.rgsServerUrl == rgs.value)
    }
}

class ConnectionRecoveryOwnerChanged : AppError("The wallet changed. Open connection recovery again.")
