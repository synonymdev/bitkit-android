package to.bitkit.ui.screens.wallets.receive

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.ReceiveAdditionalLiquidityAction
import to.bitkit.models.ReceiveLiquiditySource
import to.bitkit.repositories.BlocktankRepo
import to.bitkit.repositories.BlocktankState
import to.bitkit.repositories.OfflineReceiveRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.screens.wallets.receive.EditInvoiceVM.EditInvoiceScreenEffects
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EditInvoiceVMTest : BaseUnitTest() {

    private lateinit var sut: EditInvoiceVM
    private val walletRepo: WalletRepo = mock()
    private val blocktankRepo: BlocktankRepo = mock()
    private val offlineReceiveRepo: OfflineReceiveRepo = mock()

    @Before
    fun setUp() = runBlocking {
        whenever(blocktankRepo.blocktankState).thenReturn(MutableStateFlow(BlocktankState(minCjitSats = 5_000)))
        whenever(walletRepo.inboundLiquiditySats()).thenReturn(1_000u)
        whenever(offlineReceiveRepo.showInvoice(any())).thenReturn(Result.success(Unit))
        sut = EditInvoiceVM(walletRepo, blocktankRepo, offlineReceiveRepo)
    }

    @Test
    fun `onClickContinue should emit none for auto when amount exceeds inbound`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.AUTO,
                amountSats = 10_000u,
                isGeoBlocked = false,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.None),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        verify(walletRepo, times(1)).inboundLiquiditySats()
    }

    @Test
    fun `onClickContinue should emit choose amount for spending below CJIT minimum`() = test {
        whenever(blocktankRepo.maxCjitAmountSats()).thenReturn(Result.success(100_000u))

        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 4_000u,
                isGeoBlocked = false,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.ChooseAmount),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClickContinue should emit create CJIT for spending amount within limits`() = test {
        whenever(blocktankRepo.maxCjitAmountSats()).thenReturn(Result.success(100_000u))

        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 10_000u,
                isGeoBlocked = false,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(
                    ReceiveAdditionalLiquidityAction.CreateCjit(10_000u)
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onClickContinue should emit geo blocked without fetching CJIT limits`() = test {
        sut.editInvoiceEffect.test {
            sut.onClickContinue(
                source = ReceiveLiquiditySource.SPENDING,
                amountSats = 10_000u,
                isGeoBlocked = true,
            )

            assertEquals(
                EditInvoiceScreenEffects.ApplyReceiveLiquidityAction(ReceiveAdditionalLiquidityAction.GeoBlocked),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline receive selection resets when amount changes`() = test {
        whenever(offlineReceiveRepo.canReceive(1_000uL)).thenReturn(Result.success(true))
        whenever(offlineReceiveRepo.canReceive(2_000uL)).thenReturn(Result.success(false))
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.selectOfflineReceive(true)
        assertTrue(sut.offlineReceive.value.isSelected)

        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 2_000uL)

        assertFalse(sut.offlineReceive.value.isSelected)
        assertFalse(sut.offlineReceive.value.isAvailable)
    }

    @Test
    fun `savings and amountless invoices never offer offline receive`() = test {
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SAVINGS, 1_000uL)
        sut.selectOfflineReceive(true)
        assertFalse(sut.offlineReceive.value.isSelected)
        sut.refreshOfflineReceive(ReceiveLiquiditySource.AUTO, 0uL)
        assertFalse(sut.offlineReceive.value.isAvailable)
        verify(offlineReceiveRepo, never()).canReceive(1_000uL)
        verify(offlineReceiveRepo, never()).canReceive(0uL)
    }

    @Test
    fun `stale eligibility result cannot enable checkbox for new amount`() = test {
        val first = CompletableDeferred<Result<Boolean>>()
        whenever(offlineReceiveRepo.canReceive(1_000uL)).doSuspendableAnswer {
            withContext(NonCancellable) { first.await() }
        }
        whenever(offlineReceiveRepo.canReceive(2_000uL)).thenReturn(Result.success(false))
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 2_000uL)
        first.complete(Result.success(true))

        assertEquals(2_000uL, sut.offlineReceive.value.amountSats)
        assertFalse(sut.offlineReceive.value.isAvailable)
    }

    @Test
    fun `offline creation awaits prepared invoice before navigating`() = test {
        val prepared = PreparedOfflineInvoice("ffor", 1_000uL, "Dinner", Long.MAX_VALUE, "hash")
        val pending = CompletableDeferred<Result<PreparedOfflineInvoice>>()
        whenever(offlineReceiveRepo.canReceive(1_000uL)).thenReturn(Result.success(true))
        whenever(offlineReceiveRepo.prepareInvoice(any())).doSuspendableAnswer { pending.await() }
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.selectOfflineReceive(true)

        sut.editInvoiceEffect.test {
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false, "Dinner")
            assertTrue(sut.isLoading.value)
            expectNoEvents()
            pending.complete(Result.success(prepared))
            assertEquals(EditInvoiceScreenEffects.OfflineInvoicePrepared(prepared), awaitItem())
            assertFalse(sut.isLoading.value)
        }
    }

    @Test
    fun `offline failure remains in editor without ordinary invoice effect`() = test {
        whenever(offlineReceiveRepo.canReceive(1_000uL)).thenReturn(Result.success(true))
        whenever(offlineReceiveRepo.prepareInvoice(any()))
            .thenReturn(Result.failure(OfflineReceiveUnavailable()))
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.selectOfflineReceive(true)

        sut.editInvoiceEffect.test {
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false)
            assertIs<EditInvoiceScreenEffects.OfflineInvoiceFailed>(awaitItem())
            expectNoEvents()
            assertFalse(sut.isLoading.value)
        }
    }

    @Test
    fun `changing amount while activation runs suppresses stale invoice`() = test {
        val prepared = PreparedOfflineInvoice("ffor", 1_000uL, "", Long.MAX_VALUE, "hash")
        val pending = CompletableDeferred<Result<PreparedOfflineInvoice>>()
        whenever(offlineReceiveRepo.canReceive(1_000uL)).thenReturn(Result.success(true))
        whenever(offlineReceiveRepo.canReceive(2_000uL)).thenReturn(Result.success(false))
        whenever(offlineReceiveRepo.prepareInvoice(any())).doSuspendableAnswer { pending.await() }
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.selectOfflineReceive(true)

        sut.editInvoiceEffect.test {
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false)
            sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 2_000uL)
            pending.complete(Result.success(prepared))
            expectNoEvents()
            assertFalse(sut.isLoading.value)
        }
    }

    @Test
    fun `failed offline preparation keeps operation identity for retry`() = test {
        whenever(offlineReceiveRepo.canReceive(1_000uL)).thenReturn(Result.success(true))
        whenever(offlineReceiveRepo.prepareInvoice(any())).thenReturn(Result.failure(OfflineReceiveUnavailable()))
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.selectOfflineReceive(true)

        sut.editInvoiceEffect.test {
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false, "Dinner")
            assertIs<EditInvoiceScreenEffects.OfflineInvoiceFailed>(awaitItem())
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false, "Dinner")
            assertIs<EditInvoiceScreenEffects.OfflineInvoiceFailed>(awaitItem())
        }

        val requests = argumentCaptor<OfflineReceiveRequest>()
        verify(offlineReceiveRepo, times(2)).prepareInvoice(requests.capture())
        assertEquals(requests.firstValue, requests.secondValue)
        assertTrue(requests.firstValue.requestId.isNotBlank())
    }

    @Test
    fun `eligibility refresh cannot downgrade selected offline intent to ordinary invoice`() = test {
        val pending = CompletableDeferred<Result<Boolean>>()
        whenever(offlineReceiveRepo.canReceive(1_000uL)).thenReturn(Result.success(true))
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
        sut.selectOfflineReceive(true)
        whenever(offlineReceiveRepo.canReceive(1_000uL)).doSuspendableAnswer { pending.await() }

        sut.editInvoiceEffect.test {
            sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL)
            assertTrue(sut.offlineReceive.value.isSelected)
            assertTrue(sut.offlineReceive.value.isChecking)
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false)
            expectNoEvents()
            pending.complete(Result.success(false))
            assertTrue(sut.offlineReceive.value.isSelected)
            assertFalse(sut.offlineReceive.value.isAvailable)
        }
        verify(walletRepo, never()).inboundLiquiditySats()
    }

    @Test
    fun `reopening prepared invoice preserves offline mode and reuses the same invoice`() = test {
        val prepared = PreparedOfflineInvoice("ffor", 1_000uL, "Dinner", Long.MAX_VALUE, "hash")
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL, initialInvoice = prepared)

        sut.editInvoiceEffect.test {
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false, "Dinner")
            assertEquals(EditInvoiceScreenEffects.OfflineInvoicePrepared(prepared), awaitItem())
        }
        verify(offlineReceiveRepo, never()).prepareInvoice(any())
        verify(offlineReceiveRepo, never()).canReceive(1_000uL)
    }

    @Test
    fun `settled or unsaved invoice cannot be reopened from editor cache`() = test {
        val prepared = PreparedOfflineInvoice("ffor", 1_000uL, "Dinner", Long.MAX_VALUE, "hash")
        val error = OfflineReceiveUnavailable()
        whenever(offlineReceiveRepo.showInvoice(prepared)).thenReturn(Result.failure(error))
        sut.refreshOfflineReceive(ReceiveLiquiditySource.SPENDING, 1_000uL, initialInvoice = prepared)

        sut.editInvoiceEffect.test {
            sut.onClickContinue(ReceiveLiquiditySource.SPENDING, 1_000uL, false, "Dinner")
            assertEquals(EditInvoiceScreenEffects.OfflineInvoiceFailed(error), awaitItem())
        }
        verify(offlineReceiveRepo, never()).prepareInvoice(any())
    }
}
