package to.bitkit.services.offline.ldk

import org.junit.Test
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.OfflineReceiveOutcome
import org.lightningdevkit.ldknode.OfflineReceivePaymentInterface
import org.lightningdevkit.ldknode.OfflineReceiveStatus
import to.bitkit.services.offline.OfflineReceiveClientException
import to.bitkit.services.offline.OfflineReceiveClientException.Kind
import to.bitkit.services.offline.OfflineReceiveClientStatus
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Compiled only with `ldkNodeLocalVersion`; exercises the generated types without loading the native library. */
class LdkOfflineReceiveClientTest {
    private val payment = FakePayment()
    private val sut = LdkOfflineReceiveClient(nodeIdProvider = { "node" }, payment = payment)

    @Test
    fun `statuses map onto the app boundary`() {
        val expected = mapOf(
            OfflineReceiveStatus.Preparing to OfflineReceiveClientStatus.Pending("Preparing"),
            OfflineReceiveStatus.AwaitingActivation to OfflineReceiveClientStatus.Pending("AwaitingActivation"),
            OfflineReceiveStatus.AwaitingWitnesses to OfflineReceiveClientStatus.Pending("AwaitingWitnesses"),
            OfflineReceiveStatus.Ready("lnbc1") to OfflineReceiveClientStatus.Ready("lnbc1"),
            OfflineReceiveStatus.Expired to OfflineReceiveClientStatus.Expired,
            OfflineReceiveStatus.Settled(OfflineReceiveOutcome.FULFILLED) to OfflineReceiveClientStatus.Settled(true),
            OfflineReceiveStatus.Settled(OfflineReceiveOutcome.FAILED) to OfflineReceiveClientStatus.Settled(false),
            OfflineReceiveStatus.Failed("boom") to OfflineReceiveClientStatus.Failed("boom"),
        )
        expected.forEach { (library, app) ->
            payment.status = library
            assertEquals(app, sut.status("id"))
            assertEquals(app, sut.prepare("id", 1_000uL, "Dinner"))
        }
        assertEquals(Triple("id", 1_000uL, "Dinner"), payment.lastPrepare)
        assertEquals("node", sut.nodeId())
    }

    @Test
    fun `exceptions map onto client exception kinds`() {
        val expected = mapOf(
            NodeException.OfflineReceiveDisabled("m") to Kind.DISABLED,
            NodeException.OfflineReceiveUnavailable("m") to Kind.UNAVAILABLE,
            NodeException.OfflineReceiveIneligible("m") to Kind.INELIGIBLE,
            NodeException.OfflineReceiveRequestNotFound("m") to Kind.REQUEST_NOT_FOUND,
            NodeException.OfflineReceiveRequestConflict("m") to Kind.REQUEST_CONFLICT,
            NodeException.NotRunning("m") to Kind.NOT_RUNNING,
            NodeException.InvalidAmount("m") to Kind.OTHER,
        )
        expected.forEach { (library, kind) ->
            payment.error = library
            val error = assertFailsWith<OfflineReceiveClientException> { sut.canReceive(1_000uL) }
            assertEquals(kind, error.kind)
            assertEquals(library, error.cause)
            assertFailsWith<OfflineReceiveClientException> { sut.cancel("id") }
        }
        payment.error = null
        assertTrue(sut.canReceive(1_000uL))
        sut.cancel("id")
        assertEquals(listOf("id"), payment.cancelled)
    }

    private class FakePayment : OfflineReceivePaymentInterface {
        var status: OfflineReceiveStatus = OfflineReceiveStatus.Preparing
        var error: NodeException? = null
        var lastPrepare: Triple<String, ULong, String>? = null
        val cancelled = mutableListOf<String>()

        override fun canReceive(amountMsat: ULong): Boolean {
            error?.let { throw it }
            return true
        }

        override fun cancel(requestId: String) {
            error?.let { throw it }
            cancelled += requestId
        }

        override fun prepare(requestId: String, amountMsat: ULong, description: String): OfflineReceiveStatus {
            error?.let { throw it }
            lastPrepare = Triple(requestId, amountMsat, description)
            return status
        }

        override fun status(requestId: String): OfflineReceiveStatus {
            error?.let { throw it }
            return status
        }
    }
}
