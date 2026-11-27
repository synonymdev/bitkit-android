package to.bitkit.wear.repositories

import breez_sdk_spark.SdkEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.security.FakeDeviceLock
import to.bitkit.wear.security.FakeNetworkMonitor
import to.bitkit.wear.security.FakeSeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A wallet wipe that the process dies in, at each point between its steps, and the next launch that finishes it. The
 * fakes keep what would be on disk; a new [WalletRepo] over them is the next launch.
 */
class WalletWipeRecoveryTest : BaseUnitTest() {
    private enum class Step { Note, Seed, Spark, Settings }

    private val seed = WalletSeed(mnemonic = "words", passphrase = null)
    private val seedStore = FakeSeedStore(seed)
    private val sparkService = mock<WatchSparkService>()
    private val settingsStore = mock<SettingsStore>()

    private var hasSparkStorage = true
    private var isWipePending = false
    private var isRemovedNoticePending = false
    private var hasSettings = true
    private var killedBefore: Step? = null
    private var sparkFailure: Throwable? = null

    @Before
    fun setUp() {
        whenever(sparkService.events).thenReturn(MutableSharedFlow<SdkEvent>())
        whenever(sparkService.isSupported).thenReturn(true)
        whenever { sparkService.wipeStorage() }.thenAnswer {
            dieBefore(Step.Spark)
            sparkFailure?.let { throw it }
            hasSparkStorage = false
        }
        whenever { settingsStore.isWipePending() }.thenAnswer { isWipePending }
        whenever { settingsStore.isRemovedNoticePending() }.thenAnswer { isRemovedNoticePending }
        whenever { settingsStore.beginWipe(any()) }.thenAnswer {
            dieBefore(Step.Note)
            isWipePending = true
            if (it.getArgument(0)) isRemovedNoticePending = true
        }
        whenever { settingsStore.finishWipe() }.thenAnswer {
            dieBefore(Step.Settings)
            isWipePending = false
            hasSettings = false
        }
        seedStore.onWipe = { dieBefore(Step.Seed) }
    }

    @Test
    fun `a process that dies before the wipe is noted leaves the wallet whole`() = test {
        wipeDyingBefore(Step.Note)

        assertTrue(launch().recover().isSuccess)

        assertNotNull(seedStore.seed)
        assertTrue(hasSparkStorage)
        assertTrue(hasSettings)
    }

    @Test
    fun `a process that dies after the note finishes the wipe at the next launch`() = test {
        wipeDyingBefore(Step.Seed)
        assertNotNull(seedStore.seed)
        assertTrue(isWipePending)

        assertTrue(launch().recover().isSuccess)

        assertWiped()
    }

    @Test
    fun `a process that dies after the seed is deleted finishes the wipe at the next launch`() = test {
        wipeDyingBefore(Step.Spark)
        assertNull(seedStore.seed)
        assertTrue(hasSparkStorage)

        assertTrue(launch().recover().isSuccess)

        assertWiped()
    }

    @Test
    fun `a process that dies before the settings are cleared finishes the wipe at the next launch`() = test {
        wipeDyingBefore(Step.Settings)
        assertNull(seedStore.seed)
        assertFalse(hasSparkStorage)
        assertTrue(isWipePending)

        assertTrue(launch().recover().isSuccess)

        assertWiped()
    }

    @Test
    fun `the seed is always deleted before spark storage`() = test {
        wipeDyingBefore(Step.Spark)

        assertNull(seedStore.seed)
        assertTrue(hasSparkStorage)
    }

    @Test
    fun `a wipe whose spark storage could not be deleted is finished at the next launch`() = test {
        sparkFailure = SparkError.WipeFailed("dir")

        assertIs<SparkError.WipeFailed>(launch().wipe().exceptionOrNull())
        assertNull(seedStore.seed)
        assertTrue(isWipePending)

        sparkFailure = null
        assertTrue(launch().recover().isSuccess)

        assertWiped()
    }

    @Test
    fun `a wipe that fails again at the next launch stays noted`() = test {
        sparkFailure = SparkError.WipeFailed("dir")
        launch().wipe()

        assertIs<SparkError.WipeFailed>(launch().recover().exceptionOrNull())

        assertTrue(isWipePending)
    }

    @Test
    fun `a lost key wipe that the process dies in still shows its notice once it is finished`() = test {
        seedStore.seed = null
        val lostKeyStore = FakeSeedStore(seed)
        whenever { sparkService.connect(any(), any()) }.thenAnswer { throw SeedStoreError.KeyInvalidated() }
        killedBefore = Step.Spark
        runCatching { launch(lostKeyStore).start() }
        killedBefore = null
        assertTrue(isRemovedNoticePending)

        val next = launch(lostKeyStore)
        assertTrue(next.recover().isSuccess)

        assertTrue(next.isRemovedNoticePending())
        assertFalse(next.hasWallet())
        assertFalse(hasSparkStorage)
        assertFalse(isWipePending)
    }

    @Test
    fun `spark storage without a seed is deleted at launch`() = test {
        seedStore.seed = null

        assertTrue(launch().recover().isSuccess)

        assertFalse(hasSparkStorage)
        assertTrue(hasSettings)
    }

    @Test
    fun `a launch with a wallet and no wipe under way deletes nothing`() = test {
        assertTrue(launch().recover().isSuccess)

        assertEquals(seed, seedStore.seed)
        assertTrue(hasSparkStorage)
        verify(sparkService, never()).wipeStorage()
    }

    @Test
    fun `a restore after a wipe that the process died in finishes the wipe first`() = test {
        wipeDyingBefore(Step.Spark)
        val other = WalletSeed(mnemonic = "other words", passphrase = null)

        assertTrue(launch().restore(other).isSuccess)

        assertEquals(other, seedStore.seed)
        assertFalse(hasSparkStorage)
        assertFalse(isWipePending)
    }

    private suspend fun wipeDyingBefore(step: Step) {
        killedBefore = step
        val result = runCatching { launch().wipe() }
        assertIs<CancellationException>(result.exceptionOrNull())
        killedBefore = null
    }

    private fun dieBefore(step: Step) {
        if (killedBefore == step) throw CancellationException("process killed before '$step'")
    }

    private fun launch(store: FakeSeedStore = seedStore) = WalletRepo(
        testDispatcher,
        store,
        sparkService,
        settingsStore,
        FakeDeviceLock(),
        FakeNetworkMonitor(),
    )

    private fun assertWiped() {
        assertNull(seedStore.seed)
        assertFalse(hasSparkStorage)
        assertFalse(hasSettings)
        assertFalse(isWipePending)
    }
}
