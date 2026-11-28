package to.bitkit.wear.services

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.FakeOwnInvoiceStore
import to.bitkit.wear.services.SparkFixtures.IDENTITY
import to.bitkit.wear.services.SparkFixtures.OTHER_IDENTITY
import to.bitkit.wear.services.SparkFixtures.PAYMENT_HASH
import to.bitkit.wear.services.SparkFixtures.PROVIDER_HINT
import to.bitkit.wear.services.SparkFixtures.PROVIDER_NODE
import to.bitkit.wear.services.SparkFixtures.SPARK_ADDRESS_HINT
import to.bitkit.wear.services.SparkFixtures.hint
import to.bitkit.wear.services.SparkFixtures.invoiceInput
import to.bitkit.wear.services.SparkFixtures.sparkAddressInput
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfSendGuardTest {
    private val sparkService = mock<WatchSparkService>()
    private val store = FakeOwnInvoiceStore()
    private val sut = WalletSelfSendGuard(sparkService, store)

    @Test
    fun `a spark address of this wallet's identity is its own, in either case`() = runTest {
        whenever(sparkService.identityPubkey()).thenReturn(IDENTITY)

        assertTrue(sut.isOwn(sparkAddressInput(IDENTITY)))
        assertTrue(sut.isOwn(sparkAddressInput(IDENTITY.uppercase())))
        assertFalse(sut.isOwn(sparkAddressInput(OTHER_IDENTITY)))
    }

    @Test
    fun `an invoice whose spark address hint names this wallet's identity is its own`() = runTest {
        whenever(sparkService.identityPubkey()).thenReturn(IDENTITY)

        assertTrue(sut.isOwn(invoiceInput(hints = listOf(PROVIDER_HINT, SPARK_ADDRESS_HINT))))
    }

    @Test
    fun `an invoice with another wallet's spark address hint is not its own`() = runTest {
        whenever(sparkService.identityPubkey()).thenReturn(IDENTITY)
        val other = hint(OTHER_IDENTITY, WalletSelfSendGuard.SPARK_ADDRESS_HINT_SCID)

        assertFalse(sut.isOwn(invoiceInput(hints = listOf(PROVIDER_HINT, other))))
    }

    @Test
    fun `the identity under another short channel id is an ordinary route hint`() = runTest {
        whenever(sparkService.identityPubkey()).thenReturn(IDENTITY)

        assertFalse(sut.isOwn(invoiceInput(hints = listOf(hint(IDENTITY, "1x1026x2")))))
    }

    @Test
    fun `an invoice as bitkit creates it names only spark's provider, so the identity does not recognise it`() =
        runTest {
            whenever(sparkService.identityPubkey()).thenReturn(IDENTITY)

            assertFalse(sut.isOwn(invoiceInput()))
            // The provider's node is every Spark wallet's payee, never this wallet's identity.
            assertFalse(WalletSelfSendGuard.namesIdentity(invoiceInput(), PROVIDER_NODE))
        }

    @Test
    fun `an invoice this wallet remembers creating is its own, without a hint and in either case`() = runTest {
        whenever(sparkService.identityPubkey()).thenReturn(IDENTITY)
        store.add(PAYMENT_HASH, ULong.MAX_VALUE)

        assertTrue(sut.isOwn(invoiceInput()))
        assertTrue(sut.isOwn(invoiceInput(paymentHash = PAYMENT_HASH.uppercase())))
        assertFalse(sut.isOwn(invoiceInput(paymentHash = "ff".repeat(32))))
    }

    @Test
    fun `a remembered invoice is its own even when the identity cannot be read`() = runTest {
        whenever(sparkService.identityPubkey()).doSuspendableAnswer { throw SparkError.NotConnected() }
        store.add(PAYMENT_HASH, ULong.MAX_VALUE)

        assertTrue(sut.isOwn(invoiceInput()))
    }

    @Test
    fun `an unknown identity fails the check instead of passing the input`() = runTest {
        whenever(sparkService.identityPubkey()).doSuspendableAnswer { throw SparkError.NotConnected() }

        assertFailsWith<SparkError.NotConnected> { sut.isOwn(invoiceInput()) }
        assertFailsWith<SparkError.NotConnected> { sut.isOwn(sparkAddressInput()) }
    }
}
