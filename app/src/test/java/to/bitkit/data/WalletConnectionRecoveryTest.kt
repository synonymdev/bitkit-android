package to.bitkit.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.data.entities.ConfigEntity
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.SettingsBackupV1
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class WalletConnectionRecoveryTest : BaseUnitTest() {
    private val settings = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
    private val keychain = mock<Keychain>()
    private val db = mock<AppDb>()
    private val binding = mock<VssStoreIdProvider>()
    private val config = MutableStateFlow(listOf(ConfigEntity()))
    private var record: String? = null
    private var pending = false
    private val markers = mutableMapOf<String, String>()
    private lateinit var sut: WalletConnectionRecovery

    @Before
    fun setUp() = runBlocking {
        settings.reset()
        whenever(db.configDao()).thenReturn(mock())
        whenever(db.configDao().getAll()).thenReturn(config)
        whenever(binding.getBackupWalletBinding(any())).thenReturn("original-wallet")
        whenever(keychain.loadString(eq(WalletConnectionRecovery.KEY), any())).thenAnswer { record }
        whenever(keychain.exists(any())).thenAnswer {
            val key = it.getArgument<String>(0)
            (pending && key == Keychain.Key.PAYKIT_PENDING_BACKUP_RESTORE.name) || markers.containsKey(key)
        }
        whenever(keychain.upsertString(any(), any(), eq(0))).thenAnswer {
            val key = it.getArgument<String>(0)
            markers[key] = it.getArgument(1)
            Unit
        }
        whenever(keychain.delete(any(), eq(0))).thenAnswer {
            markers.remove(it.getArgument<String>(0))
            Unit
        }
        whenever(keychain.upsertString(eq(WalletConnectionRecovery.KEY), any(), eq(0))).thenAnswer {
            record = it.getArgument(1)
            Unit
        }
        whenever(keychain.delete(WalletConnectionRecovery.KEY, 0)).thenAnswer { record = null }
        sut = instance()
    }

    @After
    fun tearDown() = runBlocking { settings.reset() }

    private fun instance(store: SettingsStore = settings) = WalletConnectionRecovery(keychain, db, binding, store)

    @Test
    fun `queued wallet generation is revoked during same seed replacement`() = test {
        val original = sut.currentGeneration()
        assertTrue(sut.isCurrentGeneration(original))

        sut.withIdentityChange {
            assertFalse(sut.isCurrentGeneration(original))
            assertNull(sut.currentGeneration())
        }

        assertFalse(sut.isCurrentGeneration(original))
        assertTrue(sut.isCurrentGeneration(sut.currentGeneration()))
    }

    @Test
    fun `new instance preserves selected fields through remote restore without changing unrelated settings`() = test {
        sut.withOperation(sut.captureOwner()) { it.save(electrum = "ssl://chosen.example:50002") }
        val restarted = instance()
        restarted.withOperation(restarted.captureOwner()) { session ->
            val remote = SettingsData(electrumServer = "ssl://old.example:50002", selectedCurrency = "EUR")
            session.restoreSettings(SettingsBackupV1(createdAt = 0, settings = remote))
            val revision = session.replay()
            assertEquals("ssl://chosen.example:50002", settings.data.first().electrumServer)
            assertEquals("EUR", settings.data.first().selectedCurrency)
            session.complete(revision)
        }
        assertNull(record)
    }

    @Test
    fun `separate saves preserve both choices and an absent default RGS remains explicit`() = test {
        sut.withOperation(sut.captureOwner()) { session ->
            session.save(electrum = "tcp://chosen.example:50001")
            session.save(rgs = WalletConnectionRecovery.RgsChoice(null))
            session.restoreSettings(
                SettingsBackupV1(
                    createdAt = 0,
                    settings = SettingsData(rgsServerUrl = "https://broken.example")
                )
            )
            val restored = settings.data.first()
            assertEquals("tcp://chosen.example:50001", restored.electrumServer)
            assertNull(restored.rgsServerUrl)
        }
    }

    @Test
    fun `pending wallet restore retains correction after successful node start`() = test {
        pending = true
        sut.withOperation(sut.captureOwner()) { session ->
            session.save(electrum = Env.electrumServerUrl)
            session.complete(session.replay())
        }
        assertNotNull(record)
    }

    @Test
    fun `older completion cannot erase a more recent correction`() = test {
        sut.withOperation(sut.captureOwner()) { session ->
            session.save(electrum = "ssl://first.example:50002")
            val firstRevision = session.replay()
            session.save(electrum = "ssl://second.example:50002")
            session.complete(firstRevision)
        }
        assertNotNull(record)
        assertEquals("ssl://second.example:50002", settings.data.first().electrumServer)
    }

    @Test
    fun `settings failure retains durable choice for restart replay`() = test {
        val unavailable = mock<SettingsStore>()
        whenever(unavailable.update(any())).thenAnswer { throw AppError("settings unavailable") }
        val failed = instance(unavailable)
        assertFailsWith<AppError> {
            failed.withOperation(failed.captureOwner()) { it.save(electrum = "ssl://chosen.example:50002") }
        }
        assertNotNull(record)
        val restarted = instance()
        restarted.withOperation(restarted.captureOwner()) { it.replay() }
        assertEquals("ssl://chosen.example:50002", settings.data.first().electrumServer)
    }

    @Test
    fun `unacknowledged intent write never changes settings`() = test {
        whenever(keychain.upsertString(eq(WalletConnectionRecovery.KEY), any(), eq(0)))
            .thenAnswer { throw AppError("keychain unavailable") }
        val before = settings.data.first()
        assertFailsWith<AppError> {
            sut.withOperation(sut.captureOwner()) { it.save(electrum = "ssl://chosen.example:50002") }
        }
        assertNull(record)
        assertEquals(before, settings.data.first())
    }

    @Test
    fun `corrupt or future record fails closed without replacing its bytes`() = test {
        for (value in listOf("not-json", """{"version":99}""")) {
            record = value
            assertFailsWith<IllegalArgumentException> {
                sut.withOperation(sut.captureOwner()) { it.save(electrum = Env.electrumServerUrl) }
            }
            assertEquals(value, record)
        }
    }

    @Test
    fun `unavailable record is not treated as an absent override`() = test {
        whenever(keychain.loadString(WalletConnectionRecovery.KEY, 0)).thenAnswer { throw AppError("read failed") }
        assertFailsWith<AppError> { sut.withOperation(sut.captureOwner()) { it.replay() } }
    }

    @Test
    fun `wallet replacement after confirmation cannot save to new identity`() = test {
        val owner = sut.captureOwner()
        whenever(binding.getBackupWalletBinding(0)).thenReturn("replacement-wallet")
        assertFailsWith<ConnectionRecoveryOwnerChanged> {
            sut.withOperation(owner) { it.save(electrum = Env.electrumServerUrl) }
        }
        assertNull(record)
    }

    @Test
    fun `active index replacement cannot reuse old confirmation`() = test {
        val owner = sut.captureOwner()
        config.value = listOf(ConfigEntity(1))
        assertFailsWith<ConnectionRecoveryOwnerChanged> { sut.withOperation(owner) { it.replay() } }
    }

    @Test
    fun `same seed replacement revokes earlier owner and escaped session`() = test {
        val owner = sut.captureOwner()
        lateinit var ended: WalletConnectionRecovery.Session
        sut.withOperation(owner) { ended = it }
        assertFailsWith<IllegalStateException> { ended.replay() }
        sut.withIdentityChange { }
        assertFailsWith<CancellationException> { sut.withOperation(owner) { it.replay() } }
        sut.withOperation(sut.captureOwner()) { it.save(electrum = Env.electrumServerUrl) }
        assertNotNull(record)
    }

    @Test
    fun `cancellation during validation does not persist a choice`() = test {
        val entered = CompletableDeferred<Unit>()
        val validation = CompletableDeferred<Unit>()
        val operation = launch {
            sut.withOperation(sut.captureOwner()) { session ->
                entered.complete(Unit)
                validation.await()
                session.save(electrum = Env.electrumServerUrl)
            }
        }
        entered.await()
        operation.cancel()
        operation.join()
        assertNull(record)
    }

    @Test
    fun `view cancellation after durable write starts still commits normal settings`() = test {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(keychain.upsertString(eq(WalletConnectionRecovery.KEY), any(), eq(0))).doSuspendableAnswer {
            record = it.getArgument(1)
            writing.complete(Unit)
            release.await()
        }
        val operation = launch {
            sut.withOperation(sut.captureOwner()) { it.save(electrum = "ssl://chosen.example:50002") }
        }
        writing.await()
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertEquals("ssl://chosen.example:50002", settings.data.first().electrumServer)
        assertNotNull(record)
    }

    @Test
    fun `wipe drains noncancellable intent before erasing and rejects queued old owners`() = test {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = sut.captureOwner()
        whenever(keychain.upsertString(eq(WalletConnectionRecovery.KEY), any(), eq(0))).doSuspendableAnswer {
            record = it.getArgument(1)
            writing.complete(Unit)
            release.await()
        }
        val first = launch { sut.withOperation(owner) { it.save(electrum = Env.electrumServerUrl) } }
        writing.await()
        var secondRan = false
        val second = launch { sut.withOperation(owner) { secondRan = true } }
        runCurrent()
        var wiped = false
        val wipe = async {
            sut.withIdentityChange {
                record = null
                wiped = true
            }
        }
        runCurrent()
        assertFalse(wiped)
        release.complete(Unit)
        first.join()
        second.join()
        wipe.await()
        assertTrue(wiped)
        assertFalse(secondRan)
        assertNull(record)
    }

    @Test
    fun `canceled identity change releases admission after draining the old writer`() = test {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(keychain.upsertString(eq(WalletConnectionRecovery.KEY), any(), eq(0))).doSuspendableAnswer {
            record = it.getArgument(1)
            writing.complete(Unit)
            release.await()
        }
        val writer = launch {
            sut.withOperation(sut.captureOwner()) { it.save(electrum = Env.electrumServerUrl) }
        }
        writing.await()
        var replacementRan = false
        val replacement = launch { sut.withIdentityChange { replacementRan = true } }
        runCurrent()
        replacement.cancel()
        release.complete(Unit)
        writer.join()
        replacement.join()
        assertFalse(replacementRan)
        assertTrue(runCatching { sut.captureOwner() }.isSuccess)
    }

    @Test
    fun `startup can satisfy node dependent restoration without taking its operation lock`() = test {
        val waitingForNode = CompletableDeferred<Unit>()
        val nodeRunning = CompletableDeferred<Unit>()
        val owner = sut.captureOwner()
        val restore = launch {
            sut.withOperation(owner) {
                waitingForNode.complete(Unit)
                nodeRunning.await()
            }
        }
        waitingForNode.await()
        sut.withStartup(owner) { nodeRunning.complete(Unit) }
        restore.join()
    }

    @Test
    fun `older startup replay cannot overwrite a newly acknowledged save`() = test {
        val state = MutableStateFlow(SettingsData())
        val store = mock<SettingsStore>()
        whenever(store.data).thenReturn(state)
        var pauseReplay = false
        val replayWriting = CompletableDeferred<Unit>()
        val releaseReplay = CompletableDeferred<Unit>()
        whenever(store.update(any())).doSuspendableAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            if (pauseReplay) {
                pauseReplay = false
                replayWriting.complete(Unit)
                releaseReplay.await()
            }
            state.value = transform(state.value)
        }
        val coordinator = instance(store)
        val owner = coordinator.captureOwner()
        coordinator.withOperation(owner) { it.save(electrum = "ssl://first.example:50002") }
        pauseReplay = true
        val replay = launch { coordinator.withStartup(owner) { it.replay() } }
        replayWriting.await()
        var saved = false
        val save = launch {
            coordinator.withOperation(owner) { it.save(electrum = "ssl://second.example:50002") }
            saved = true
        }
        runCurrent()
        assertFalse(saved)
        releaseReplay.complete(Unit)
        replay.join()
        save.join()
        assertTrue(saved)
        assertEquals("ssl://second.example:50002", state.value.electrumServer)
    }

    @Test
    fun `ordinary admission without corrections does not derive backup credentials`() = test {
        whenever(binding.getBackupWalletBinding(0)).thenAnswer { throw AppError("binding unavailable") }
        sut.withCurrentWallet { assertEquals(null, it.replay()) }
        sut.withStartup(sut.captureRuntimeOwner()) { assertEquals(null, it.replay()) }
    }

    @Test
    fun `runtime admission still validates persisted correction credentials`() = test {
        sut.withOperation(sut.captureOwner()) { it.save(electrum = "ssl://corrected.example:50002") }
        whenever(binding.getBackupWalletBinding(0)).thenAnswer { throw AppError("binding unavailable") }
        assertFailsWith<AppError> { sut.withCurrentWallet { it.replay() } }
        assertTrue(record != null)
    }

    @Test
    fun `capture requested during replacement never adopts its new lifecycle`() = test {
        val replacing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacement = launch {
            sut.withIdentityChange {
                replacing.complete(Unit)
                release.await()
            }
        }
        replacing.await()
        val capture = async { runCatching { sut.captureOwner() } }
        runCurrent()
        val rejectedWithoutWaiting = capture.isCompleted
        release.complete(Unit)
        replacement.join()
        val result = capture.await()
        assertTrue(rejectedWithoutWaiting)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `rejected conditional migration preserves the current wallet admission`() = test {
        val owner = sut.captureOwner()
        assertFailsWith<CancellationException> {
            sut.withIdentityChange(isStillNeeded = { false }) { error("stale migration must not run") }
        }
        sut.withOperation(owner) { assertEquals(null, it.replay()) }
    }
}
