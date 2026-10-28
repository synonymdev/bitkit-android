package to.bitkit.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRemovalStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `pending profile permits a complete local receipt snapshot but pending wipe does not`() = runTest {
        val store = ProfileRemovalStore.inMemoryForTesting()
        val id = UUID.randomUUID().toString()
        store.withAccess(id) {
            store.begin(id)
            assertTrue(store.localDisconnectAvailable.first())
            assertEquals("historical receipt", store.withReceiptSnapshot { "historical receipt" })
        }
        store.withAccess(id, ProfileRemovalScope.WALLET_WIPE) { store.begin(id) }
        assertFalse(store.localDisconnectAvailable.first())
        assertFailsWith<ProfileRemovalPending> { store.withReceiptSnapshot { error("must not copy") } }
        assertFailsWith<IllegalStateException> { store.withAccess(id) { store.complete(id) } }
        assertEquals(id, store.pendingAttempt())
    }

    @Test
    fun `local cleanup waits for the whole snapshot and never exposes its partial clear`() = runTest {
        val store = ProfileRemovalStore.inMemoryForTesting()
        val id = UUID.randomUUID().toString()
        store.withAccess(id) { store.begin(id) }
        val copying = CompletableDeferred<Unit>()
        val releaseCopy = CompletableDeferred<Unit>()
        val source = mutableListOf("first", "second")
        val snapshot = async {
            store.withReceiptSnapshot {
                val first = source.first()
                copying.complete(Unit)
                releaseCopy.await()
                listOf(first, source.last())
            }
        }
        copying.await()
        var cleared = false
        val cleanup = launch {
            store.withAccess(id) {
                store.withReceiptCleanup {
                    source.clear()
                    cleared = true
                }
            }
        }
        runCurrent()
        assertFalse(cleared)
        releaseCopy.complete(Unit)
        assertEquals(listOf("first", "second"), snapshot.await())
        cleanup.join()
        assertTrue(cleared)
        assertFailsWith<ReceiptAssociationsUnavailable> { store.withReceiptSnapshot { source.toList() } }
        store.withAccess(id) { store.complete(id) }
        assertEquals(emptyList(), store.withReceiptSnapshot { source.toList() })
    }

    @Test
    fun `partial local cleanup remains blocked after a real DataStore restart`() = runTest {
        val file = folder.newFile("partial-removal").also { it.delete() }
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = ProfileRemovalStore(DataStoreFactory.create(ProfileRemovalSerializer, scope = firstScope) { file })
        val id = UUID.randomUUID().toString()
        try {
            first.withAccess(id) {
                first.begin(id)
                assertFailsWith<IOException> {
                    first.withReceiptCleanup { throw IOException("private state unavailable") }
                }
            }
        } finally {
            requireNotNull(firstScope.coroutineContext[Job]).cancelAndJoin()
        }
        val second =
            ProfileRemovalStore(DataStoreFactory.create(ProfileRemovalSerializer, scope = backgroundScope) { file })
        assertEquals(id, second.pendingAttempt())
        assertFailsWith<ReceiptAssociationsUnavailable> { second.withReceiptSnapshot { error("partial state") } }
        second.withAccess(id) {
            second.withReceiptCleanup { }
            second.complete(id)
        }
        assertEquals("complete", second.withReceiptSnapshot { "complete" })
    }

    @Test
    fun `legacy marker and unavailable storage never become absent receipt associations`() = runTest {
        val state = MutableStateFlow(ProfileRemovalState(attemptId = UUID.randomUUID().toString()))
        var unavailable = false
        val store = ProfileRemovalStore(object : DataStore<ProfileRemovalState> {
            override val data = flow {
                if (unavailable) throw IOException("unavailable")
                emit(state.value)
            }
            override suspend fun updateData(transform: suspend (ProfileRemovalState) -> ProfileRemovalState) =
                transform(state.value).also { state.value = it }
        })
        assertFailsWith<ProfileRemovalPending> { store.withReceiptSnapshot { error("legacy state") } }
        assertFalse(store.localDisconnectAvailable.first())
        unavailable = true
        assertFalse(store.localDisconnectAvailable.first())
        assertTrue(
            assertFailsWith<ProfileRemovalPending> {
                store.withReceiptSnapshot { error("unavailable state") }
            }.cause is IOException
        )
    }

    @Test
    fun `observation fails closed with backoff and restarts after explicit durable completion`() = runTest {
        val id = UUID.randomUUID().toString()
        val state = MutableStateFlow(ProfileRemovalState(attemptId = id, scope = ProfileRemovalScope.PROFILE))
        var unavailable = true
        var reads = 0
        val store = ProfileRemovalStore(object : DataStore<ProfileRemovalState> {
            override val data = flow {
                reads++
                if (unavailable) throw IOException("Local storage unavailable")
                emitAll(state)
            }
            override suspend fun updateData(transform: suspend (ProfileRemovalState) -> ProfileRemovalState) =
                transform(state.value).also { state.value = it }
        })
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch { store.pending.collect { observed += it } }
        runCurrent()
        advanceTimeBy(1_750)
        runCurrent()
        assertEquals(4, reads)
        assertEquals(true, observed.last())
        unavailable = false
        store.withAccess(id) { store.complete(id) }
        runCurrent()
        assertEquals(false, observed.last())
        assertNull(store.pendingAttempt())
    }

    @Test
    fun `observation recovers from prolonged read errors without a cleanup mutation`() = runTest {
        var available = false
        var reads = 0
        val store = ProfileRemovalStore(object : DataStore<ProfileRemovalState> {
            override val data = flow {
                reads++
                if (!available) throw IOException("Local storage unavailable")
                emit(ProfileRemovalState())
            }
            override suspend fun updateData(
                transform: suspend (ProfileRemovalState) -> ProfileRemovalState
            ): ProfileRemovalState =
                error("Read-only recovery must not mutate cleanup state")
        })
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch { store.pending.collect { observed += it } }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(true, observed.last())
        available = true
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(false, observed.last())
        assertTrue(reads <= 12, "Local recovery must retain capped backoff")
    }

    @Test
    fun `restart retains pending without authorizing cleanup or another attempt`() = runTest {
        val file = folder.newFile("removal").also { it.delete() }
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = ProfileRemovalStore(DataStoreFactory.create(ProfileRemovalSerializer, scope = firstScope) { file })
        val attempt = UUID.randomUUID().toString()
        try {
            first.withAccess(attempt) {
                first.begin(attempt)
                first.requireAccess()
            }
        } finally {
            requireNotNull(firstScope.coroutineContext[Job]).cancelAndJoin()
        }
        val second =
            ProfileRemovalStore(DataStoreFactory.create(ProfileRemovalSerializer, scope = backgroundScope) { file })
        assertEquals(attempt, second.pendingAttempt())
        assertFailsWith<ProfileRemovalPending> { second.requireAccess() }
        assertFailsWith<ProfileRemovalPending> { second.requireNoPending() }
        assertFailsWith<IllegalStateException> { second.complete(attempt) }
        assertFailsWith<IllegalStateException> { second.withAccess(UUID.randomUUID().toString()) {} }
        second.withAccess(attempt) {
            second.requireAccess()
            second.complete(attempt)
        }
        assertNull(second.pendingAttempt())
    }

    @Test
    fun `an escaped access context cannot authorize work after its scope finishes`() = runTest {
        val store = ProfileRemovalStore.inMemoryForTesting()
        val id = UUID.randomUUID().toString()
        val stale = store.withAccess(id) {
            store.begin(id)
            currentCoroutineContext().minusKey(Job)
        }
        assertFailsWith<ProfileRemovalPending> { withContext(stale) { store.requireAccess() } }
        assertEquals(id, store.pendingAttempt())
    }

    @Test
    fun `a foreign coroutine cannot use a pending owner's access`() = runTest {
        val store = ProfileRemovalStore.inMemoryForTesting()
        val id = UUID.randomUUID().toString()
        store.withAccess(id) {
            store.begin(id)
            val foreign = backgroundScope.async { assertFailsWith<ProfileRemovalPending> { store.requireAccess() } }
            foreign.await()
            store.requireAccess()
            assertFailsWith<IllegalStateException> { store.complete(UUID.randomUUID().toString()) }
            assertEquals(id, store.pendingAttempt())
        }
    }

    @Test
    fun `malformed persisted marker becomes stable pending recovery across restart`() = runTest {
        val file = folder.newFile("malformed").also { it.writeText("{broken") }
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = ProfileRemovalStore(
            DataStoreFactory.create(
                ProfileRemovalSerializer,
                corruptionHandler = profileRemovalCorruptionHandler(),
                scope = firstScope,
            ) { file }
        )
        val recovered = try {
            assertNotNull(first.pendingAttempt()).also {
                assertFailsWith<ProfileRemovalPending> { first.requireNoPending() }
                assertFailsWith<IllegalStateException> { first.complete(it) }
            }
        } finally {
            requireNotNull(firstScope.coroutineContext[Job]).cancelAndJoin()
        }
        val second = ProfileRemovalStore(
            DataStoreFactory.create(
                ProfileRemovalSerializer,
                corruptionHandler = profileRemovalCorruptionHandler(),
                scope = backgroundScope,
            ) { file }
        )
        assertEquals(recovered, second.pendingAttempt())
        assertFalse(second.localDisconnectAvailable.first())
        assertFailsWith<ProfileRemovalPending> { second.requireReceiptAccountingAccess() }
        assertFailsWith<IllegalStateException> { second.withAccess(recovered) { second.complete(recovered) } }
        second.withAccess(recovered, ProfileRemovalScope.WALLET_WIPE) {
            second.begin(recovered)
            second.complete(recovered)
        }
        assertNull(second.pendingAttempt())
    }

    @Test
    fun `future version with incompatible fields stays intact and cannot be mistaken for corrupt v1`() = runTest {
        for (version in listOf("2", "\"1\"", "null", "1.5")) {
            val bytes = "{\"version\":$version,\"attemptId\":{\"future\":true}}".toByteArray()
            val file = folder.newFile().also { it.writeBytes(bytes) }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val store = ProfileRemovalStore(
                DataStoreFactory.create(
                    ProfileRemovalSerializer,
                    corruptionHandler = profileRemovalCorruptionHandler(),
                    scope = scope,
                ) { file }
            )
            try {
                val failure = assertFailsWith<IOException> { store.pendingAttempt() }
                assertTrue(
                    generateSequence<Throwable>(failure) { it.cause }
                        .any { it is UnsupportedProfileRemovalFormat }
                )
                assertTrue(bytes.contentEquals(file.readBytes()))
            } finally {
                requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            }
        }
    }

    @Test
    fun `input read failure is not classified as replaceable corruption`() = runTest {
        val failure = IOException("storage unavailable")
        val stream = object : java.io.InputStream() {
            override fun read(): Int = throw failure
        }
        assertEquals(failure, assertFailsWith<IOException> { ProfileRemovalSerializer.readFrom(stream) })
    }

    @Test
    fun `corrupt recovery state is never interpreted as no pending work`() = runTest {
        for (bytes in listOf(
            "not json".toByteArray(),
            "{\"attemptId\":\"bad\"}".toByteArray(),
            "{\"scope\":\"WALLET_WIPE\"}".toByteArray(),
            "{\"attemptId\":null,\"localCleanupStarted\":true}".toByteArray(),
        )) {
            assertFailsWith<CorruptionException> { ProfileRemovalSerializer.readFrom(ByteArrayInputStream(bytes)) }
        }
        assertFailsWith<CorruptionException> {
            ProfileRemovalSerializer.readFrom(
                ByteArrayInputStream(byteArrayOf(-1))
            )
        }
        assertEquals(ProfileRemovalState(), ProfileRemovalSerializer.readFrom(ByteArrayInputStream("{}".toByteArray())))
    }
}
