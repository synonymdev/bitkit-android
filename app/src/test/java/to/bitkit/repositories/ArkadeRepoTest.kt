package to.bitkit.repositories

import app.cash.turbine.test
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lightningdevkit.ldknode.Event
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.env.Env
import to.bitkit.models.ArkadeExitBackupV1
import to.bitkit.services.ArkadeBalance
import to.bitkit.services.ArkadeEvent
import to.bitkit.services.ArkadeHistoryEntry
import to.bitkit.services.ArkadeInfo
import to.bitkit.services.ArkadeService
import to.bitkit.services.ArkadeSwap
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArkadeRepoTest : BaseUnitTest() {
    private val arkadeService = mock<ArkadeService>()
    private val activityRepo = mock<ActivityRepo>()
    private val arkadeEvents = MutableSharedFlow<ArkadeEvent>()

    private fun createSut() = ArkadeRepo(
        bgDispatcher = testDispatcher,
        arkadeService = arkadeService,
        activityRepo = { activityRepo },
    )

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Env.initAppStoragePath(tempFolder.root.absolutePath)
        whenever { arkadeService.connect(any(), any(), anyOrNull()) }.thenReturn(ArkadeInfo("ark1", "bc1p", "bitcoin"))
        whenever { arkadeService.balance() }.thenReturn(ArkadeBalance(available = 1_000))
        whenever { arkadeService.history() }.thenReturn(emptyList())
        whenever { arkadeService.vtxos() }.thenReturn(emptyList())
        whenever { arkadeService.swaps() }.thenReturn(emptyList())
        whenever { arkadeService.lightningAddress() }.thenReturn(null)
        whenever(arkadeService.events).thenReturn(arkadeEvents)
        whenever { activityRepo.getActivity(any(), any()) }.thenReturn(Result.success(null))
        whenever { activityRepo.upsertActivity(any()) }.thenReturn(Result.success(Unit))
        whenever(arkadeService.isSupported).thenReturn(true)
        whenever(arkadeService.isConnected).thenReturn(true)
        whenever(arkadeService.networkName).thenReturn("bitcoin")
    }

    @Test
    fun `scans the operator for funds only on the wallet's first start`() = test {
        createSut().start(0)
        verify(arkadeService).connect(any(), eq(true), anyOrNull())

        createSut().start(0)
        verify(arkadeService).connect(any(), eq(false), anyOrNull())
    }

    @Test
    fun `marks a deposit boarded once its boarding settles`() = test {
        whenever { arkadeService.history() }.thenReturn(
            listOf(
                ArkadeHistoryEntry(
                    "k",
                    boardingTxid = "txid",
                    type = "RECEIVED",
                    amount = 2_500,
                    settled = true,
                    createdAt = 1
                )
            ),
        )
        val sut = createSut()
        sut.onDepositBroadcast("txid", 2_500uL)

        sut.start(0)

        val deposit = sut.state.value.deposits.single()
        assertTrue(deposit.isClaimed)
        assertEquals(1_000uL, sut.state.value.balanceSats)
    }

    @Test
    fun `lists a broadcast deposit as pending until the wallet lists it`() = test {
        val sut = createSut()

        sut.onDepositBroadcast("txid", 2_500uL)

        val deposit = sut.state.value.deposits.single()
        assertEquals("txid", deposit.txid)
        assertFalse(deposit.isClaimed)
        assertEquals(2_500uL, sut.state.value.pendingDepositSats)
    }

    @Test
    fun `raises a claimed receive as a received payment`() = test {
        val sut = createSut()
        sut.start(0)

        sut.nodeEvents.test {
            arkadeEvents.emit(ArkadeEvent.Swap(swap(direction = "receive", outcome = "claimed")))
            val event = assertIs<Event.PaymentReceived>(awaitItem())
            assertEquals("hash", event.paymentHash)
            assertEquals(995_000uL, event.amountMsat)
        }
        verify(activityRepo).upsertActivity(any())
    }

    @Test
    fun `raises a paid send with its fee`() = test {
        val sut = createSut()
        sut.start(0)

        sut.nodeEvents.test {
            arkadeEvents.emit(ArkadeEvent.Swap(swap(direction = "send", outcome = "paid")))
            val event = assertIs<Event.PaymentSuccessful>(awaitItem())
            assertEquals(5_000uL, event.feePaidMsat)
        }
    }

    @Test
    fun `keeps a send that needs recovery pending`() = test {
        val sut = createSut()
        sut.start(0)

        sut.nodeEvents.test {
            arkadeEvents.emit(ArkadeEvent.Swap(swap(direction = "send", outcome = "needs_recovery")))
            expectNoEvents()
        }
        argumentCaptor<Activity> {
            verify(activityRepo).upsertActivity(capture())
            assertEquals(PaymentState.PENDING, assertIs<Activity.Lightning>(firstValue).v1.status)
        }
    }

    @Test
    fun `refuses to pay an invoice without an amount`() = test {
        val result = createSut().payInvoice("lnbc1notaninvoice", 1_000uL)

        assertTrue(result.isFailure)
        verify(arkadeService, never()).pay(any(), anyOrNull())
    }

    @Test
    fun `skips a wallet backup from another network`() = test {
        createSut().restoreExitBackup(ArkadeExitBackupV1(createdAt = 1, network = "mutinynet", exitState = "{}"), 0)

        assertFalse(File(Env.arkadeExitRestorePath(0)).exists())
    }

    @Test
    fun `imports a wallet backup on the next start and scans again`() = test {
        createSut().start(0)
        val sut = createSut()

        sut.restoreExitBackup(ArkadeExitBackupV1(createdAt = 1, network = "bitcoin", exitState = "{\"version\":1}"), 0)

        verify(arkadeService).connect(any(), eq(true), eq("{\"version\":1}"))
        assertFalse(File(Env.arkadeExitRestorePath(0)).exists())
    }

    private fun swap(direction: String, outcome: String) = ArkadeSwap(
        id = "rfq:1",
        direction = direction,
        outcome = outcome,
        giveSat = 1_000,
        takeSat = 995,
        feeSat = 5,
        bolt11 = "lnbc1",
        paymentHash = "hash",
        createdAt = 1,
    )
}
