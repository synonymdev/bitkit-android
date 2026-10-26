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
    internal val generation: Long get() = observationRevision.value

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val pending: Flow<Boolean> = observationRevision.flatMapLatest {
        store.data.map { it.attemptId != null }
            .retryWhen { _, attempt ->
                emit(true)
                delay((250L shl attempt.coerceAtMost(8).toInt()).coerceAtMost(60_000).milliseconds)
                true
            }
    }

    internal suspend fun pendingAttempt(): String? = store.data.first().attemptId

    internal suspend fun requireNoPending() {
        if (pendingAttempt() != null) throw ProfileRemovalPending()
    }

    internal suspend fun requireAccess() {
        val pending = pendingAttempt() ?: return
        val access = currentCoroutineContext()[Access]
        if (access?.store !== this || access.attemptId != pending || !access.active.get()) {
            throw ProfileRemovalPending()
        }
        access.job.ensureActive()
    }

    /** Only for local completion after acknowledged credential teardown, while outer removal ownership is retained. */
    internal suspend fun requireCompletionAccess() {
        val pending = pendingAttempt() ?: return
        val access = currentCoroutineContext()[Access]
        if (access?.store !== this || access.attemptId != pending || !access.active.get()) {
            throw ProfileRemovalPending()
        }
    }

    internal suspend fun <T> withAccess(attemptId: String, operation: suspend () -> T): T {
        require(UUID.fromString(attemptId).toString() == attemptId) { "Invalid profile removal attempt" }
        val pending = pendingAttempt()
        check(pending == null || pending == attemptId) { "Another profile removal needs local recovery" }
        val access = Access(this, attemptId, requireNotNull(currentCoroutineContext()[Job]))
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
            it.copy(attemptId = attemptId)
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
            check(it.attemptId == attemptId) { "Profile removal attempt changed" }
            ProfileRemovalState()
        }
        observationRevision.update { it + 1 }
    }

    private class Access(
        val store: ProfileRemovalStore,
        val attemptId: String,
        val job: Job,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Access>
        val active = AtomicBoolean(true)
    }
}

@Serializable
internal data class ProfileRemovalState(val version: Int = 1, val attemptId: String? = null)

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
            json.decodeFromJsonElement<ProfileRemovalState>(envelope).also {
                require(it.attemptId == null || UUID.fromString(it.attemptId).toString() == it.attemptId) {
                    "Invalid profile removal attempt"
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
    "Profile cleanup is pending. Disconnect the profile to finish local recovery.",
    cause,
)
