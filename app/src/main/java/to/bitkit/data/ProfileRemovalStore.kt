package to.bitkit.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import to.bitkit.di.json
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

private val Context.profileRemovalDataStore: DataStore<ProfileRemovalState> by dataStore(
    fileName = "profile_removal.json",
    serializer = ProfileRemovalSerializer,
    corruptionHandler = profileRemovalCorruptionHandler(),
)

/** Local recovery admission only. A pending attempt is never evidence that a remote mutation succeeded. */
@Singleton
class ProfileRemovalStore internal constructor(private val store: DataStore<ProfileRemovalState>) {
    internal companion object {
        /** Used only by the existing service's injected-handle unit-test constructor. */
        fun inMemoryForTesting() = ProfileRemovalStore(object : DataStore<ProfileRemovalState> {
            private val state = MutableStateFlow(ProfileRemovalState())
            private val mutex = Mutex()
            override val data: Flow<ProfileRemovalState> = state
            override suspend fun updateData(transform: suspend (ProfileRemovalState) -> ProfileRemovalState) =
                mutex.withLock { transform(state.value).also { state.value = it } }
        })
    }

    @Inject
    constructor(@ApplicationContext context: Context) : this(context.profileRemovalDataStore)

    private val observationRevision = MutableStateFlow(0L)
    private val receiptSnapshotMutex = Mutex()
    internal val generation: Long get() = observationRevision.value

    @OptIn(ExperimentalCoroutinesApi::class)
    private val observedState: Flow<ProfileRemovalState?> = observationRevision.flatMapLatest {
        store.data.map<ProfileRemovalState, ProfileRemovalState?> { it }
            .retryWhen { _, attempt ->
                emit(null)
                delay((250L shl attempt.coerceAtMost(8).toInt()).coerceAtMost(60_000).milliseconds)
                true
            }
    }

    internal val pending: Flow<Boolean> = observedState.map { it == null || it.attemptId != null }

    internal val localDisconnectAvailable: Flow<Boolean> = observedState.map {
        it?.attemptId != null && it.scope == ProfileRemovalScope.PROFILE
    }

    internal suspend fun pendingAttempt(): String? = store.data.first().attemptId

    internal suspend fun requireNoPending() {
        if (pendingAttempt() != null) throw ProfileRemovalPending()
    }

    internal suspend fun requireAccess() {
        val state = store.data.first()
        val pending = state.attemptId ?: return
        val access = currentCoroutineContext()[Access]
        if (access?.store !== this || access.attemptId != pending || !access.active.get() ||
            state.scope != access.scope
        ) {
            throw ProfileRemovalPending()
        }
        access.job.ensureActive()
    }

    /** Only for local completion after acknowledged credential teardown, while outer removal ownership is retained. */
    internal suspend fun requireCompletionAccess() {
        val state = store.data.first()
        val pending = state.attemptId ?: return
        val access = currentCoroutineContext()[Access]
        if (access?.store !== this || access.attemptId != pending || !access.active.get() ||
            state.scope != access.scope
        ) {
            throw ProfileRemovalPending()
        }
    }

    internal suspend fun <T> withAccess(
        attemptId: String,
        scope: ProfileRemovalScope = ProfileRemovalScope.PROFILE,
        operation: suspend () -> T,
    ): T {
        require(UUID.fromString(attemptId).toString() == attemptId) { "Invalid profile removal attempt" }
        val state = store.data.first()
        val pending = state.attemptId
        check(pending == null || pending == attemptId) { "Another profile removal needs local recovery" }
        check(pending == null || state.scope == scope || scope == ProfileRemovalScope.WALLET_WIPE) {
            "Wallet cleanup must complete before profile recovery"
        }
        val access = Access(this, attemptId, scope, requireNotNull(currentCoroutineContext()[Job]))
        try {
            return withContext(access) { operation() }
        } finally {
            access.active.set(false)
        }
    }

    internal suspend fun begin(attemptId: String) {
        val access = currentCoroutineContext()[Access]
        check(access?.store === this && access.attemptId == attemptId && access.active.get()) {
            "Profile removal admission is required"
        }
        access.job.ensureActive()
        store.updateData {
            check(it.attemptId == null || it.attemptId == attemptId) { "Profile removal attempt changed" }
            check(
                it.attemptId == null || it.scope == access.scope ||
                    access.scope == ProfileRemovalScope.WALLET_WIPE
            ) { "Wallet cleanup cannot become profile cleanup" }
            it.copy(attemptId = attemptId, scope = access.scope)
        }
        observationRevision.update { it + 1 }
    }

    /** Caller must retain removal ownership until all acknowledged teardown's local persistence has completed. */
    internal suspend fun complete(attemptId: String) {
        val access = currentCoroutineContext()[Access]
        check(access?.store === this && access.attemptId == attemptId && access.active.get()) {
            "Profile removal admission is required"
        }
        store.updateData {
            check(it.attemptId == attemptId && it.scope == access.scope) { "Profile removal attempt changed" }
            ProfileRemovalState()
        }
        observationRevision.update { it + 1 }
    }

    /** Copy local associations only; never hold this gate over SDK, Core or address derivation. */
    internal suspend fun <T> withReceiptSnapshot(read: suspend () -> T): T = receiptSnapshotMutex.withLock {
        val state = readReceiptState()
        if (state.localCleanupStarted) throw ReceiptAssociationsUnavailable()
        val snapshot = read()
        check(readReceiptState() == state) { "Receipt source changed while being copied" }
        snapshot
    }

    private suspend fun readReceiptState(): ProfileRemovalState {
        val state = runSuspendCatching { store.data.first() }.getOrElse { throw ProfileRemovalPending(it) }
        if (state.attemptId != null && state.scope != ProfileRemovalScope.PROFILE) throw ProfileRemovalPending()
        return state
    }

    internal suspend fun requireReceiptAccountingAccess() { readReceiptState() }

    /** A failed local clear remains unavailable after restart; admitted snapshots keep their own lifetime. */
    internal suspend fun <T> withReceiptCleanup(clear: suspend () -> T): T = receiptSnapshotMutex.withLock {
        armReceiptCleanup()
        clear()
    }

    internal suspend fun beginLocalCleanup() = receiptSnapshotMutex.withLock { armReceiptCleanup() }

    private suspend fun armReceiptCleanup() {
        requireCompletionAccess()
        store.updateData { state ->
            check(state.attemptId != null) { "Durable cleanup admission is required" }
            state.copy(localCleanupStarted = true)
        }
        observationRevision.update { it + 1 }
    }

    private class Access(
        val store: ProfileRemovalStore,
        val attemptId: String,
        val scope: ProfileRemovalScope,
        val job: Job,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Access>
        val active = AtomicBoolean(true)
    }
}

@Serializable
internal data class ProfileRemovalState(
    val version: Int = 1,
    val attemptId: String? = null,
    val scope: ProfileRemovalScope? = null,
    val localCleanupStarted: Boolean = false,
)

@Serializable
internal enum class ProfileRemovalScope { PROFILE, WALLET_WIPE }

/** Malformed local admission data is replaced only with a new, still-blocking recovery obligation. */
internal fun profileRemovalCorruptionHandler() = ReplaceFileCorruptionHandler {
    ProfileRemovalState(attemptId = UUID.randomUUID().toString())
}

internal class UnsupportedProfileRemovalFormat : IOException("Update Bitkit to recover this profile cleanup state")

internal object ProfileRemovalSerializer : Serializer<ProfileRemovalState> {
    override val defaultValue = ProfileRemovalState()

    override suspend fun readFrom(input: InputStream): ProfileRemovalState {
        val bytes = input.readBytes()
        return try {
            val envelope = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: throw CorruptionException("Profile recovery state is not an object")
            envelope["version"]?.let { version ->
                if (version !is JsonPrimitive || version.isString || version.intOrNull != 1) {
                    throw UnsupportedProfileRemovalFormat()
                }
            }
            envelope["scope"]?.let { scope ->
                if (scope is JsonPrimitive && scope.content != "null" &&
                    ProfileRemovalScope.entries.none { it.name == scope.content }
                ) {
                    throw UnsupportedProfileRemovalFormat()
                }
            }
            json.decodeFromJsonElement<ProfileRemovalState>(envelope).also {
                require(it.attemptId == null || UUID.fromString(it.attemptId).toString() == it.attemptId) {
                    "Invalid profile removal attempt"
                }
                require(it.attemptId != null || (it.scope == null && !it.localCleanupStarted)) {
                    "Cleanup state requires a pending attempt"
                }
            }
        } catch (error: CharacterCodingException) {
            throw CorruptionException("Profile recovery state has invalid text encoding", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("Profile recovery state could not be read", error)
        }
    }

    override suspend fun writeTo(t: ProfileRemovalState, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

internal class ProfileRemovalPending(cause: Throwable? = null) : AppError(
    "Local recovery is pending. Open the profile for recovery options.",
    cause,
)

internal class ReceiptAssociationsUnavailable : AppError("Receipt associations are being cleared")
