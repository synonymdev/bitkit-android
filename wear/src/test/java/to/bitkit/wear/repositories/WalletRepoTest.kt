package to.bitkit.wear.repositories

import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.SdkEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.security.SeedStore
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.services.SparkError
import to.bitkit.wear.services.WatchSparkService
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletRepoTest : BaseUnitTest() {
    private val seedStore = mock<SeedStore>()
    private val sparkService = mock<WatchSparkService>()
    private val settingsStore = mock<SettingsStore>()
    private val seed = WalletSeed(mnemonic = "words", passphrase = null)

    private lateinit var sut: WalletRepo

    @Before
    fun setUp() {
        whenever(sparkService.events).thenReturn(MutableSharedFlow<SdkEvent>())
        sut = WalletRepo(testDispatcher, seedStore, sparkService, settingsStore)
    }

    @Test
    fun `start connects the stored seed and loads the balance`() = test {
        whenever(seedStore.load()).thenReturn(Result.success(seed))
        whenever(sparkService.getInfo()).thenReturn(info(21_000uL))

        val result = sut.start()

        assertTrue(result.isSuccess)
        verify(sparkService).connect(seed)
        verify(sparkService).syncWallet()
        assertEquals(WalletState(WalletStatus.Ready, 21_000uL), sut.walletState.value)
    }

    @Test
    fun `start without a seed fails and never connects`() = test {
        whenever(seedStore.load()).thenReturn(Result.failure(SeedStoreError.NotImplemented()))

        val result = sut.start()

        assertIs<SeedStoreError.NotImplemented>(result.exceptionOrNull())
        verify(sparkService, never()).connect(any())
        assertEquals(WalletStatus.Error, sut.walletState.value.status)
        assertNull(sut.walletState.value.balanceSats)
    }

    @Test
    fun `wipe deletes spark storage, then the seed and the settings`() = test {
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.wipe()

        assertTrue(result.isSuccess)
        inOrder(sparkService, seedStore, settingsStore) {
            verify(sparkService).wipeStorage()
            verify(seedStore).wipe()
            verify(settingsStore).reset()
        }
        assertFalse(sparkService.isWiping)
        assertEquals(WalletState(), sut.walletState.value)
    }

    @Test
    fun `wipe still deletes the seed when spark storage fails, and reports the failure`() = test {
        whenever(sparkService.wipeStorage()).thenAnswer { throw SparkError.WipeFailed("dir") }
        whenever(seedStore.wipe()).thenReturn(Result.success(Unit))

        val result = sut.wipe()

        assertIs<SparkError.WipeFailed>(result.exceptionOrNull())
        verify(seedStore).wipe()
    }

    private fun info(balanceSats: ULong) = GetInfoResponse(
        identityPubkey = "pubkey",
        balanceSats = balanceSats,
        tokenBalances = emptyMap(),
        recoverableFundsSats = 0uL,
        lastSyncError = null,
        lastSyncedAt = 1uL,
    )
}
