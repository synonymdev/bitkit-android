package to.bitkit.repositories

import com.synonym.bitkitcore.BoltzException
import com.synonym.bitkitcore.BoltzPairInfo
import com.synonym.bitkitcore.PubkySwapConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.models.PubkySwapNetwork
import to.bitkit.models.PubkySwapProvider
import to.bitkit.services.BoltzService
import to.bitkit.test.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PubkySwapRepoTest : BaseUnitTest() {
    companion object {
        private const val IDENTITY = "pubky8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"
        private const val SECRET = "secret"
        private const val BOLT11 = "lnbcrt1holdinvoice"
        private const val SWAP_ID = "swap1"
        private const val PENDING_SWAPS = "cannot switch while pending Pubky swaps exist"
        private val PROVIDER = PubkySwapProvider(
            publicKey = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xy",
            network = PubkySwapNetwork.REGTEST,
        )
        private val OTHER_PROVIDER = PubkySwapProvider(
            publicKey = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo",
            network = PubkySwapNetwork.REGTEST,
        )
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val boltzService = mock<BoltzService>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val lightningRepo = mock<LightningRepo>()
    private val identity = MutableStateFlow<String?>(IDENTITY)
    private val savedProvider = MutableStateFlow<PubkySwapProvider?>(null)
    private var activeConfig: PubkySwapConfig? = null
    private var hasPendingSwaps = false
    private lateinit var sut: PubkySwapRepo

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever(pubkyRepo.publicKey).thenReturn(identity)
        whenever { pubkyRepo.withActiveSecretKey<Any?>(any()) }.doSuspendableAnswer {
            val block = it.getArgument<suspend (String, String) -> Any?>(0)
            Result.success(block(IDENTITY, SECRET))
        }
        whenever(settingsStore.pubkySwapProvider).thenReturn(savedProvider)
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        whenever { settingsStore.setPubkySwapProvider(anyOrNull()) }.doSuspendableAnswer {
            savedProvider.value = it.getArgument(0)
        }
        whenever(boltzService.activePubkyConfig).thenAnswer { activeConfig }
        whenever { boltzService.configurePubky(any(), any()) }.doSuspendableAnswer {
            activeConfig = it.getArgument(0)
        }
        whenever { boltzService.disconnectPubky() }.doSuspendableAnswer { activeConfig = null }
        whenever { boltzService.preparePubkySwitch() }.doSuspendableAnswer {
            if (hasPendingSwaps) throw BoltzException.InvalidInput(PENDING_SWAPS)
            activeConfig = null
        }
        whenever { boltzService.reverseLimits() }.thenReturn(mock<BoltzPairInfo>())
        sut = PubkySwapRepo(
            ioDispatcher = testDispatcher,
            boltzService = boltzService,
            pubkyRepo = pubkyRepo,
            settingsStore = settingsStore,
            lightningRepo = lightningRepo,
        )
    }

    @Test
    fun `connect saves the provider only after it answers`() = test {
        val result = sut.connect(PROVIDER)

        assertTrue(result.isSuccess)
        assertEquals(PROVIDER, savedProvider.value)
        assertEquals(PROVIDER.publicKey, activeConfig?.provider)
        assertEquals(PubkySwapState.Connected(PROVIDER, IDENTITY), sut.state.value)
        val inOrder = inOrder(boltzService, settingsStore)
        inOrder.verify(boltzService).configurePubky(any(), any())
        inOrder.verify(boltzService).reverseLimits()
        inOrder.verify(settingsStore).setPubkySwapProvider(PROVIDER)
        inOrder.verify(settingsStore).update(any())
    }

    @Test
    fun `connect configures the bridge with the swap policy and its own store`() = test {
        sut.connect(PROVIDER)

        val config = checkNotNull(activeConfig)
        assertEquals(PubkySwapRepo.POLICY.maxFeeBps, config.maxFeeBps)
        assertEquals(PubkySwapRepo.POLICY.maxAmountSat, config.maxAmountSat)
        assertTrue(config.dataDir.startsWith(tempFolder.root.resolve("pubky-swaps").absolutePath))
        assertTrue(config.dataDir.endsWith(PROVIDER.publicKey))
    }

    @Test
    fun `connect keeps the previous provider when the new one does not answer`() = test {
        sut.connect(PROVIDER)
        val previous = activeConfig
        whenever { boltzService.reverseLimits() }.doSuspendableAnswer { throw BoltzException.InvalidInput("offline") }

        val result = sut.connect(OTHER_PROVIDER)

        assertTrue(result.isFailure)
        assertEquals(PROVIDER, savedProvider.value)
        assertEquals(previous, activeConfig)
        assertEquals(PubkySwapState.Connected(PROVIDER, IDENTITY), sut.state.value)
    }

    @Test
    fun `connect is refused while swaps of the previous provider are pending`() = test {
        sut.connect(PROVIDER)
        val previous = activeConfig
        hasPendingSwaps = true

        val result = sut.connect(OTHER_PROVIDER)

        assertEquals(PubkySwapError.PendingSwaps, result.exceptionOrNull())
        assertEquals(PROVIDER, savedProvider.value)
        assertEquals(previous, activeConfig)
    }

    @Test
    fun `connect without a Pubky identity does not configure the bridge`() = test {
        identity.value = null

        val result = sut.connect(PROVIDER)

        assertEquals(PubkySecretAccessError.NotSignedIn, result.exceptionOrNull())
        assertNull(savedProvider.value)
        verify(boltzService, never()).configurePubky(any(), any())
    }

    @Test
    fun `connect rejects a provider for another network`() = test {
        val result = sut.connect(PROVIDER.copy(network = PubkySwapNetwork.SIGNET))

        assertEquals(PubkySwapError.UnsupportedNetwork, result.exceptionOrNull())
        verify(boltzService, never()).configurePubky(any(), any())
    }

    @Test
    fun `signing out detaches the bridge`() = test {
        sut.connect(PROVIDER)

        identity.value = null
        advanceUntilIdle()

        assertNull(activeConfig)
        assertEquals(PubkySwapState.IdentityMissing(PROVIDER), sut.state.value)
    }

    @Test
    fun `signing out keeps the bridge while swaps are pending`() = test {
        sut.connect(PROVIDER)
        val previous = activeConfig
        hasPendingSwaps = true

        identity.value = null
        advanceUntilIdle()

        assertEquals(previous, activeConfig)
        assertEquals(PubkySwapState.SwitchBlocked(null), sut.state.value)
    }

    @Test
    fun `disconnectProvider is refused while swaps are pending`() = test {
        sut.connect(PROVIDER)
        hasPendingSwaps = true

        val result = sut.disconnectProvider()

        assertEquals(PubkySwapError.PendingSwaps, result.exceptionOrNull())
        assertEquals(PROVIDER, savedProvider.value)
    }

    @Test
    fun `disconnectProvider forgets the provider`() = test {
        sut.connect(PROVIDER)

        val result = sut.disconnectProvider()

        assertTrue(result.isSuccess)
        assertNull(savedProvider.value)
        assertNull(activeConfig)
        assertEquals(PubkySwapState.NoProvider, sut.state.value)
    }

    @Test
    fun `payHoldInvoice records the payment before sending it`() = test {
        whenever { boltzService.markPaymentStarted(SWAP_ID) }.thenReturn(true)
        stubPayInvoice()

        val result = sut.payHoldInvoice(SWAP_ID, BOLT11)

        assertEquals(HoldInvoicePayment.Dispatched("payment-hash"), result.getOrThrow())
        val inOrder = inOrder(boltzService, lightningRepo)
        inOrder.verify(boltzService).markPaymentStarted(SWAP_ID)
        inOrder.verify(lightningRepo).payInvoice(BOLT11, null)
    }

    @Test
    fun `payHoldInvoice does not pay an invoice whose payment already started`() = test {
        whenever { boltzService.markPaymentStarted(SWAP_ID) }.thenReturn(false)
        stubPayInvoice()

        val result = sut.payHoldInvoice(SWAP_ID, BOLT11)

        assertEquals(HoldInvoicePayment.AlreadyStarted, result.getOrThrow())
        verify(lightningRepo, never()).payInvoice(BOLT11, null)
    }

    @Test
    fun `payHoldInvoice keeps payment failures`() = test {
        val error = IllegalStateException("route not found")
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any<suspend () -> Boolean>()) }
            .thenReturn(Result.failure(error))

        assertSame(error, sut.payHoldInvoice(SWAP_ID, BOLT11).exceptionOrNull())
    }

    @Test
    fun `shouldRunUpdates keeps updates running when recovery cannot be checked`() = test {
        whenever { boltzService.isSwapEnabled() }.thenReturn(false)
        whenever { boltzService.hasPendingRecovery() }.doSuspendableAnswer { throw BoltzException.InvalidInput("db") }

        assertTrue(sut.shouldRunUpdates())
    }

    @Test
    fun `shouldRunUpdates stops without swaps to recover`() = test {
        whenever { boltzService.isSwapEnabled() }.thenReturn(false)
        whenever { boltzService.hasPendingRecovery() }.thenReturn(false)

        assertFalse(sut.shouldRunUpdates())
    }

    @Test
    fun `takePendingConnection returns the held provider once`() {
        sut.setPendingConnection(PROVIDER)

        assertEquals(PROVIDER, sut.takePendingConnection())
        assertNull(sut.takePendingConnection())
    }

    @Test
    fun `toPubkySwapError maps core refusals`() {
        assertEquals(PubkySwapError.PendingSwaps, BoltzException.InvalidInput(PENDING_SWAPS).toPubkySwapError())
        assertEquals(
            PubkySwapError.Busy,
            BoltzException.InvalidInput("a swap operation is active").toPubkySwapError(),
        )
        assertIs<BoltzException.InvalidInput>(BoltzException.InvalidInput("other").toPubkySwapError())
    }

    private fun stubPayInvoice() {
        whenever { lightningRepo.payInvoice(any(), anyOrNull(), any<suspend () -> Boolean>()) }
            .doSuspendableAnswer {
                if (it.getArgument<suspend () -> Boolean>(2)()) {
                    lightningRepo.payInvoice(it.getArgument<String>(0), it.getArgument<ULong?>(1))
                } else {
                    Result.failure(PaymentAbortedBeforeSend())
                }
            }
        whenever { lightningRepo.payInvoice(BOLT11, null) }.thenReturn(Result.success("payment-hash"))
    }
}
