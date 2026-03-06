package to.bitkit.repositories

import app.cash.turbine.test
import org.junit.Before
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingPaymentRepoTest : BaseUnitTest() {

    private lateinit var sut: PendingPaymentRepo

    @Before
    fun setUp() {
        sut = PendingPaymentRepo()
    }

    @Test
    fun `track adds hash to pending set`() {
        sut.track("hash1")
        assertTrue(sut.isPending("hash1"))
    }

    @Test
    fun `track is idempotent for duplicate hash`() {
        sut.track("hash1")
        sut.track("hash1")
        assertEquals(1, sut.state.value.pendingPayments.size)
    }

    @Test
    fun `isPending returns true after track`() {
        sut.track("hash1")
        assertTrue(sut.isPending("hash1"))
    }

    @Test
    fun `isPending returns false for untracked hash`() {
        assertFalse(sut.isPending("unknown"))
    }

    @Test
    fun `resolve returns true for tracked hash`() {
        sut.track("hash1")
        assertTrue(sut.resolve(PendingPaymentResolution.Success("hash1")))
    }

    @Test
    fun `resolve returns false for untracked hash`() {
        assertFalse(sut.resolve(PendingPaymentResolution.Success("unknown")))
    }

    @Test
    fun `resolve emits Success on resolution flow`() = test {
        sut.track("hash1")
        sut.resolution.test {
            sut.resolve(PendingPaymentResolution.Success("hash1"))
            val item = awaitItem()
            assertIs<PendingPaymentResolution.Success>(item)
            assertEquals("hash1", item.paymentHash)
        }
    }

    @Test
    fun `resolve emits Failure on resolution flow`() = test {
        sut.track("hash1")
        sut.resolution.test {
            sut.resolve(PendingPaymentResolution.Failure("hash1"))
            val item = awaitItem()
            assertIs<PendingPaymentResolution.Failure>(item)
            assertEquals("hash1", item.paymentHash)
        }
    }

    @Test
    fun `setActiveHash and isActive returns true for active hash`() {
        sut.setActiveHash("hash1")
        assertTrue(sut.isActive("hash1"))
    }

    @Test
    fun `isActive returns false for non-active hash`() {
        sut.setActiveHash("hash1")
        assertFalse(sut.isActive("hash2"))
    }

    @Test
    fun `setActiveHash null clears active hash`() {
        sut.setActiveHash("hash1")
        sut.setActiveHash(null)
        assertFalse(sut.isActive("hash1"))
    }

    @Test
    fun `resolve clears activeHash when pendingPayments becomes empty`() {
        sut.track("hash1")
        sut.setActiveHash("hash1")
        sut.resolve(PendingPaymentResolution.Success("hash1"))
        assertNull(sut.state.value.activeHash)
    }

    @Test
    fun `resolve keeps activeHash when pendingPayments is not empty`() {
        sut.track("hash1")
        sut.track("hash2")
        sut.setActiveHash("hash1")
        sut.resolve(PendingPaymentResolution.Success("hash2"))
        assertEquals("hash1", sut.state.value.activeHash)
    }
}
