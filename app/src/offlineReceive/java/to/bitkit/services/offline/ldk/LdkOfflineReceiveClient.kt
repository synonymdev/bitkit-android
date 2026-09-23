package to.bitkit.services.offline.ldk

import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.OfflineReceiveOutcome
import org.lightningdevkit.ldknode.OfflineReceivePaymentInterface
import org.lightningdevkit.ldknode.OfflineReceiveStatus
import to.bitkit.services.LightningService
import to.bitkit.services.offline.OfflineReceiveClient
import to.bitkit.services.offline.OfflineReceiveClientException
import to.bitkit.services.offline.OfflineReceiveClientException.Kind
import to.bitkit.services.offline.OfflineReceiveClientProvider
import to.bitkit.services.offline.OfflineReceiveClientStatus
import javax.inject.Inject

/** Adapts the generated `OfflineReceivePayment` handler to the app's [OfflineReceiveClient] boundary. */
class LdkOfflineReceiveClient(
    private val nodeIdProvider: () -> String,
    private val payment: OfflineReceivePaymentInterface,
) : OfflineReceiveClient {

    constructor(node: Node) : this(nodeIdProvider = { node.nodeId() }, payment = node.offlineReceive())

    override fun nodeId(): String = nodeIdProvider()

    override fun canReceive(amountMsat: ULong): Boolean = call { payment.canReceive(amountMsat) }

    override fun prepare(requestId: String, amountMsat: ULong, description: String): OfflineReceiveClientStatus =
        call { payment.prepare(requestId, amountMsat, description).toClientStatus() }

    override fun status(requestId: String): OfflineReceiveClientStatus =
        call { payment.status(requestId).toClientStatus() }

    override fun cancel(requestId: String) = call { payment.cancel(requestId) }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: NodeException) {
        throw e.toClientException()
    }
}

class LdkOfflineReceiveClientProvider @Inject constructor(
    private val lightningService: LightningService,
) : OfflineReceiveClientProvider {
    override fun client(): OfflineReceiveClient? = lightningService.node?.let { LdkOfflineReceiveClient(it) }
}

internal fun OfflineReceiveStatus.toClientStatus(): OfflineReceiveClientStatus = when (this) {
    OfflineReceiveStatus.Preparing -> OfflineReceiveClientStatus.Pending("Preparing")
    OfflineReceiveStatus.AwaitingActivation -> OfflineReceiveClientStatus.Pending("AwaitingActivation")
    OfflineReceiveStatus.AwaitingWitnesses -> OfflineReceiveClientStatus.Pending("AwaitingWitnesses")
    is OfflineReceiveStatus.Ready -> OfflineReceiveClientStatus.Ready(bolt11)
    OfflineReceiveStatus.Expired -> OfflineReceiveClientStatus.Expired
    is OfflineReceiveStatus.Settled -> OfflineReceiveClientStatus.Settled(outcome == OfflineReceiveOutcome.FULFILLED)
    is OfflineReceiveStatus.Failed -> OfflineReceiveClientStatus.Failed(reason)
}

internal fun NodeException.toClientException(): OfflineReceiveClientException {
    val kind = when (this) {
        is NodeException.OfflineReceiveDisabled -> Kind.DISABLED
        is NodeException.OfflineReceiveUnavailable -> Kind.UNAVAILABLE
        is NodeException.OfflineReceiveIneligible -> Kind.INELIGIBLE
        is NodeException.OfflineReceiveRequestNotFound -> Kind.REQUEST_NOT_FOUND
        is NodeException.OfflineReceiveRequestConflict -> Kind.REQUEST_CONFLICT
        is NodeException.NotRunning -> Kind.NOT_RUNNING
        else -> Kind.OTHER
    }
    return OfflineReceiveClientException(kind, message, this)
}
