package to.bitkit.domain.commands

import android.content.Context
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.lightningdevkit.ldknode.Event
import org.lightningdevkit.ldknode.TransactionDetails
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.ext.create
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.WalletScope
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.services.MigrationService
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class NotifyPaymentReceivedHandlerTest : BaseUnitTest() {
    companion object {
        private val NOW = Instant.fromEpochSeconds(1_700_000_000L)
    }

    private val context: Context = mock()
    private val activityRepo: ActivityRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val clock: Clock = mock()
    private val backupRepo: BackupRepo = mock()
    private val migrationService: MigrationService = mock()
    private val isRestoring = MutableStateFlow(false)
    private val isShowingMigrationLoading = MutableStateFlow(false)

    private lateinit var sut: NotifyPaymentReceivedHandler

    @Before
    fun setUp() {
        whenever(context.getString(R.string.notification__received__title)).thenReturn("Payment Received")
        whenever(context.getString(any(), any())).thenReturn("Received amount")
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData()))
        whenever(backupRepo.isRestoring).thenReturn(isRestoring)
        whenever(migrationService.isShowingMigrationLoading).thenReturn(isShowingMigrationLoading)
        whenever { migrationService.needsPostMigrationSync() }.thenReturn(false)
        whenever(clock.now()).thenReturn(NOW)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenReturn(
            Result.success(
                ConvertedAmount(
                    value = BigDecimal("0.10"),
                    formatted = "0.10",
                    symbol = "$",
                    currency = "USD",
                    flag = "\uD83C\uDDFA\uD83C\uDDF8",
                    sats = 100L
                )
            )
        )

        sut = NotifyPaymentReceivedHandler(
            ioDispatcher = testDispatcher,
            activityRepo = activityRepo,
            backupRepo = backupRepo,
            migrationService = migrationService,
            clock = clock,
            receivedNotificationContent = ReceivedNotificationContent(
                context = context,
                currencyRepo = currencyRepo,
                settingsStore = settingsStore,
            ),
        )
    }

    @Test
    fun `lightning payment returns ShowSheet`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen(any(), eq(WalletScope.default))).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowSheet)
        assertEquals(NewTransactionSheetType.LIGHTNING, paymentResult.sheet.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, paymentResult.sheet.direction)
        assertEquals("hash123", paymentResult.sheet.paymentHashOrTxId)
        assertEquals(1000L, paymentResult.sheet.sats)
        verify(activityRepo, never()).markActivityAsSeen(any(), eq(WalletScope.default))

        val claimed = sut.claimPresentation(command)

        assertTrue(claimed)
        sut.recordPresentation(command)
        verify(activityRepo).markActivityAsSeen("paymentId123", WalletScope.default)
    }

    @Test
    fun `lightning payment returns ShowNotification when includeNotification is true`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen(any(), eq(WalletScope.default))).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Lightning(
            event = event,
            includeNotification = true,
        )

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowNotification)
        assertEquals(NewTransactionSheetType.LIGHTNING, paymentResult.sheet.type)
        assertEquals("hash123", paymentResult.sheet.paymentHashOrTxId)
        assertNotNull(paymentResult.notification)
        assertEquals("Payment Received", paymentResult.notification.title)
        verify(activityRepo, never()).markActivityAsSeen(any(), eq(WalletScope.default))

        val claimed = sut.claimPresentation(command)

        assertTrue(claimed)
        sut.recordPresentation(command)
        verify(activityRepo).markActivityAsSeen("paymentId123", WalletScope.default)
    }

    @Test
    fun `onchain payment returns ShowSheet when shouldShowReceivedSheet returns true`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 5000L
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Onchain(txid = "txid456", details = details)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.ShowSheet)
        assertEquals(NewTransactionSheetType.ONCHAIN, paymentResult.sheet.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, paymentResult.sheet.direction)
        assertEquals("txid456", paymentResult.sheet.paymentHashOrTxId)
        assertEquals(5000L, paymentResult.sheet.sats)
        verify(activityRepo, never()).markOnchainActivityAsSeen(any(), eq(WalletScope.default))

        val claimed = sut.claimPresentation(command)

        assertTrue(claimed)
        sut.recordPresentation(command)
        verify(activityRepo).markOnchainActivityAsSeen("txid456", WalletScope.default)
    }

    @Test
    fun `onchain payment returns Skip when shouldShowReceivedSheet is false`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 5000L
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Onchain(txid = "txid456", details = details)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
    }

    @Test
    fun `onchain payment calls methods in correct order`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 7500L
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Onchain(txid = "txid789", details = details)

        sut(command)
        sut.claimPresentation(command)
        sut.recordPresentation(command)

        inOrder(activityRepo) {
            verify(activityRepo).handleOnchainTransactionReceived("txid789", details)
            verify(activityRepo).shouldShowReceivedSheet("txid789", 7500uL)
            verify(activityRepo).markOnchainActivityAsSeen("txid789", WalletScope.default)
        }
    }

    @Test
    fun `onchain payment does not mark as seen when shouldShowReceivedSheet returns false`() = test {
        val details = mock<TransactionDetails> {
            on { amountSats } doReturn 5000L
        }
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Onchain(txid = "txid456", details = details)

        sut(command)

        verify(activityRepo, never()).markOnchainActivityAsSeen(any(), any())
    }

    @Test
    fun `lightning payment does not call onchain-specific methods`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen(any(), eq(WalletScope.default))).thenReturn(false)
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        sut(command)

        verify(activityRepo, never()).handleOnchainTransactionReceived(any(), any())
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
        verify(activityRepo, never()).markOnchainActivityAsSeen(any(), any())
    }

    @Test
    fun `lightning payment returns Skip when already seen`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.isActivitySeen("paymentId123", WalletScope.default)).thenReturn(true)
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).markActivityAsSeen(any(), any())
    }

    @Test
    fun `lightning payment returns Skip when paymentId is null`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn null
        }
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        val result = sut(command)

        assertTrue(result.isSuccess)
        val paymentResult = result.getOrThrow()
        assertTrue(paymentResult is NotifyPaymentReceived.Result.Skip)
    }

    @Test
    fun `recordPresentation keeps the claim when marking as seen fails`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        whenever(activityRepo.markActivityAsSeen("paymentId123", WalletScope.default))
            .thenThrow(IllegalStateException("activity store unavailable"))
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        assertTrue(sut.claimPresentation(command))
        sut.recordPresentation(command)
        assertFalse(sut.claimPresentation(command))
    }

    @Test
    fun `present retains the claim after marking as seen succeeds`() = test {
        val event = mock<Event.PaymentReceived> {
            on { paymentId } doReturn "paymentId123"
        }
        val command = NotifyPaymentReceived.Command.Lightning(event = event)
        var presentationCount = 0

        val presented = sut.present(command) { presentationCount += 1 }

        assertTrue(presented)
        assertEquals(1, presentationCount)
        verify(activityRepo).markActivityAsSeen("paymentId123", WalletScope.default)
        assertFalse(sut.claimPresentation(command))
    }

    @Test
    fun `invoke returns Skip after the payment presentation is claimed`() = test {
        val event = mock<Event.PaymentReceived> {
            on { amountMsat } doReturn 1000000uL
            on { paymentHash } doReturn "hash123"
            on { paymentId } doReturn "paymentId123"
        }
        val command = NotifyPaymentReceived.Command.Lightning(event = event)
        assertTrue(sut.claimPresentation(command))

        val result = sut(command)

        assertTrue(result.getOrThrow() is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).isActivitySeen(any(), eq(WalletScope.default))
    }

    @Test
    fun `claimPresentation leaves the payment available when presentation is not allowed`() = test {
        val event = mock<Event.PaymentReceived> {
            on { paymentId } doReturn "paymentId123"
        }
        val command = NotifyPaymentReceived.Command.Lightning(event = event)

        assertFalse(sut.claimPresentation(command) { false })
        assertTrue(sut.claimPresentation(command))
    }

    @Test
    fun `confirmed-only recent onchain receive returns ShowSheet`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidConfirmed", details = details, age = Duration.ZERO)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.ShowSheet)
        assertEquals(NewTransactionSheetType.ONCHAIN, result.sheet.type)
        assertEquals(NewTransactionSheetDirection.RECEIVED, result.sheet.direction)
        assertEquals("txidConfirmed", result.sheet.paymentHashOrTxId)
        assertEquals(5000L, result.sheet.sats)
        inOrder(activityRepo) {
            verify(activityRepo).handleOnchainTransactionConfirmed("txidConfirmed", details)
            verify(activityRepo).shouldShowReceivedSheet("txidConfirmed", 5000uL)
        }
        verify(activityRepo, never()).handleOnchainTransactionReceived(any(), any())

        assertTrue(sut.present(command) {})
        verify(activityRepo).markOnchainActivityAsSeen("txidConfirmed", WalletScope.default)
    }

    @Test
    fun `confirmed-only onchain receive does not reapply a confirmation already stored`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        whenever(activityRepo.getOnchainActivityByTxId(eq("txidStored"), eq(WalletScope.default)))
            .thenReturn(onchainActivity(txId = "txidStored", confirmed = true))
        val command = confirmedCommand(txid = "txidStored", details = details, age = Duration.ZERO)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.ShowSheet)
        verify(activityRepo, never()).handleOnchainTransactionConfirmed(any(), any())
        verify(activityRepo).shouldShowReceivedSheet("txidStored", 5000uL)
    }

    @Test
    fun `confirmed-only onchain receive applies the confirmation when the activity is still unconfirmed`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        whenever(activityRepo.getOnchainActivityByTxId(eq("txidUnconfirmed"), eq(WalletScope.default)))
            .thenReturn(onchainActivity(txId = "txidUnconfirmed", confirmed = false))
        val command = confirmedCommand(txid = "txidUnconfirmed", details = details, age = Duration.ZERO)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.ShowSheet)
        verify(activityRepo).handleOnchainTransactionConfirmed("txidUnconfirmed", details)
    }

    @Test
    fun `confirmed-only onchain receive returns ShowNotification when includeNotification is true`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(
            txid = "txidConfirmed",
            details = details,
            age = 59.minutes,
            includeNotification = true,
        )

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.ShowNotification)
        assertEquals("txidConfirmed", result.sheet.paymentHashOrTxId)
        assertEquals("Payment Received", result.notification.title)
    }

    @Test
    fun `confirmed-only onchain receive slightly ahead of the device clock returns ShowSheet`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidAhead", details = details, age = (-5).minutes)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.ShowSheet)
    }

    @Test
    fun `received then confirmed onchain payment is presented once`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val received = NotifyPaymentReceived.Command.Onchain(txid = "txidOnce", details = details)
        val confirmed = confirmedCommand(txid = "txidOnce", details = details, age = Duration.ZERO)
        var presentationCount = 0

        val receivedResult = sut(received).getOrThrow()
        assertTrue(receivedResult is NotifyPaymentReceived.Result.ShowSheet)
        assertTrue(sut.present(received) { presentationCount += 1 })

        val confirmedResult = sut(confirmed).getOrThrow()

        assertTrue(confirmedResult is NotifyPaymentReceived.Result.Skip)
        assertFalse(sut.present(confirmed) { presentationCount += 1 })
        assertEquals(1, presentationCount)
        verify(activityRepo, never()).handleOnchainTransactionConfirmed(any(), any())
    }

    @Test
    fun `confirmed-only onchain receive confirmed outside the recent window returns Skip`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidOld", details = details, age = 61.minutes)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).handleOnchainTransactionConfirmed(any(), any())
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
    }

    @Test
    fun `confirmed-only onchain receive replayed from old history returns Skip`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidReplay", details = details, age = (24 * 365).hours)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
    }

    @Test
    fun `confirmed-only onchain receive far ahead of the device clock returns Skip`() = test {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidFuture", details = details, age = (-2).hours)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
    }

    @Test
    fun `confirmed-only onchain send returns Skip`() = test {
        val details = TransactionDetails(amountSats = -5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidSent", details = details, age = Duration.ZERO)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).handleOnchainTransactionConfirmed(any(), any())
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
    }

    @Test
    fun `confirmed-only onchain receive returns Skip while a restore is in progress`() = test {
        isRestoring.value = true
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidRestore", details = details, age = Duration.ZERO)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).handleOnchainTransactionConfirmed(any(), any())
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
    }

    @Test
    fun `confirmed-only onchain receive returns Skip while a migration is in progress`() = test {
        whenever(migrationService.needsPostMigrationSync()).thenReturn(true)
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        whenever(activityRepo.shouldShowReceivedSheet(any(), any())).thenReturn(true)
        val command = confirmedCommand(txid = "txidMigration", details = details, age = Duration.ZERO)

        val result = sut(command).getOrThrow()

        assertTrue(result is NotifyPaymentReceived.Result.Skip)
        verify(activityRepo, never()).shouldShowReceivedSheet(any(), any())
    }

    @Test
    fun `from maps a confirmed onchain event to a confirmed-only command`() {
        val details = TransactionDetails(amountSats = 5000L, inputs = emptyList(), outputs = emptyList())
        val event = Event.OnchainTransactionConfirmed(
            txid = "txidMapped",
            blockHash = "blockHash",
            blockHeight = 100u,
            confirmationTime = 1_700_000_000uL,
            details = details,
        )

        val command = NotifyPaymentReceived.Command.from(event, includeNotification = true)

        assertEquals(
            NotifyPaymentReceived.Command.Onchain(
                txid = "txidMapped",
                details = details,
                confirmationTime = 1_700_000_000uL,
                includeNotification = true,
            ),
            command,
        )
    }

    private fun onchainActivity(txId: String, confirmed: Boolean) = OnchainActivity.create(
        id = txId,
        txType = PaymentType.RECEIVED,
        txId = txId,
        value = 5000uL,
        fee = 100uL,
        address = "bc1test",
        timestamp = 1_700_000_000uL,
        confirmed = confirmed,
    )

    private fun confirmedCommand(
        txid: String,
        details: TransactionDetails,
        age: Duration,
        includeNotification: Boolean = false,
    ) = NotifyPaymentReceived.Command.Onchain(
        txid = txid,
        details = details,
        confirmationTime = (NOW - age).epochSeconds.toULong(),
        includeNotification = includeNotification,
    )
}
