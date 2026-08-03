package to.bitkit.ext

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import uniffi.bark.Movement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarkMovementsTest : BaseUnitTest() {

    @Test
    fun `maps a received movement to a received lightning activity`() {
        val activity = newMovement(
            id = 7u,
            intendedBalanceSats = 25_000,
            effectiveBalanceSats = 25_000,
            lightningInvoice = "lnbc250u1p",
        ).toActivity().asLightning()

        assertEquals("bark:7", activity.id)
        assertEquals(PaymentType.RECEIVED, activity.txType)
        assertEquals(PaymentState.SUCCEEDED, activity.status)
        assertEquals(25_000uL, activity.value)
        assertEquals("lnbc250u1p", activity.invoice)
    }

    @Test
    fun `maps a negative balance delta to a sent activity with absolute value`() {
        val activity = newMovement(
            intendedBalanceSats = -12_000,
            effectiveBalanceSats = -12_000,
            offchainFeeSats = 300uL,
        ).toActivity().asLightning()

        assertEquals(PaymentType.SENT, activity.txType)
        assertEquals(12_000uL, activity.value)
        assertEquals(300uL, activity.fee)
    }

    @Test
    fun `maps bark statuses onto payment states`() {
        assertEquals(PaymentState.SUCCEEDED, newMovement(status = "successful").toActivity().asLightning().status)
        assertEquals(PaymentState.PENDING, newMovement(status = "pending").toActivity().asLightning().status)
        assertEquals(PaymentState.FAILED, newMovement(status = "failed").toActivity().asLightning().status)
        assertEquals(PaymentState.FAILED, newMovement(status = "canceled").toActivity().asLightning().status)
    }

    @Test
    fun `falls back to an ark address when there is no lightning invoice`() {
        val activity = newMovement(
            lightningInvoice = null,
            lightningOffer = null,
            sentToAddresses = listOf("ark1qexample"),
        ).toActivity().asLightning()

        assertEquals("ark1qexample", activity.invoice)
    }

    @Test
    fun `leaves the invoice blank when the movement has no destination`() {
        val activity = newMovement(
            lightningInvoice = null,
            lightningOffer = null,
            sentToAddresses = emptyList(),
        ).toActivity().asLightning()

        assertEquals("", activity.invoice)
        // The FFI Movement carries no preimage, so it must not be invented.
        assertNull(activity.preimage)
    }

    @Test
    fun `parses rfc 3339 timestamps into epoch seconds`() {
        val activity = newMovement(createdAt = "2026-08-03T10:00:00Z").toActivity().asLightning()

        assertEquals(1_785_751_200uL, activity.timestamp)
        assertEquals(activity.timestamp, activity.createdAt)
    }

    @Test
    fun `keeps the activity when a timestamp cannot be parsed`() {
        val activity = newMovement(createdAt = "not-a-timestamp").toActivity().asLightning()

        assertEquals(0uL, activity.timestamp)
        assertTrue(activity.id.startsWith(BARK_ACTIVITY_ID_PREFIX))
    }

    private fun Activity.asLightning() = (this as Activity.Lightning).v1

    @Suppress("LongParameterList")
    private fun newMovement(
        id: UInt = 1u,
        status: String = "successful",
        intendedBalanceSats: Long = 1_000,
        effectiveBalanceSats: Long = 1_000,
        offchainFeeSats: ULong = 0uL,
        sentToAddresses: List<String> = emptyList(),
        lightningInvoice: String? = null,
        lightningOffer: String? = null,
        createdAt: String = "2026-08-03T10:00:00Z",
    ) = Movement(
        id = id,
        status = status,
        subsystemName = "ark",
        subsystemKind = "send",
        metadataJson = "{}",
        intendedBalanceSats = intendedBalanceSats,
        effectiveBalanceSats = effectiveBalanceSats,
        offchainFeeSats = offchainFeeSats,
        sentToAddresses = sentToAddresses,
        receivedOnAddresses = emptyList(),
        inputVtxoIds = emptyList(),
        outputVtxoIds = emptyList(),
        exitedVtxoIds = emptyList(),
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = null,
        paymentHash = null,
        lightningInvoice = lightningInvoice,
        lightningOffer = lightningOffer,
    )
}
