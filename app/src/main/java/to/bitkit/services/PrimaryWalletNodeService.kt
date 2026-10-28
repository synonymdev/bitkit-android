package to.bitkit.services

import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.flow.first
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.TransactionDetails
import to.bitkit.data.SettingsStore
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PrimaryNode
import to.bitkit.models.WalletScope
import to.bitkit.models.safe
import to.bitkit.repositories.OnchainSendInput
import to.bitkit.utils.ServiceError
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PrimaryWalletSnapshot(
    val walletIndex: Int,
    val primary: PrimaryNode,
    val isConnected: Boolean,
    val confirmedSats: ULong,
    val confirmedInputCount: Int,
    val fundingFeeRate: ULong,
    val channels: List<ChannelDetails>,
    val hasPendingFunding: Boolean,
)

data class PrimaryFailureAcknowledgementState(
    val nodeInstanceId: String,
    val pendingEventFingerprint: String?,
)

@Singleton
class PrimaryWalletNodeService @Inject constructor(
    private val lightningService: LightningService,
    private val settingsStore: SettingsStore,
) {
    val walletIndex: Int get() = lightningService.currentWalletIndex
    private var nativeInstance: Node? = null
    private var nativeInstanceId: String? = null

    suspend fun snapshot(): PrimaryWalletSnapshot? {
        val uri = settingsStore.data.first().primaryNodeUri
        if (uri.isBlank()) return null
        val primary = PrimaryNode.parse(uri)
        return lightningService.callOnchainSend {
            val node = lightningService.node ?: return@callOnchainSend null
            if (!node.status().isRunning) return@callOnchainSend null
            val confirmedTxids = node.listPayments().mapNotNull {
                (it.kind as? PaymentKind.Onchain)?.takeIf { payment ->
                    payment.status is ConfirmationStatus.Confirmed
                }?.txid
            }.toSet()
            val confirmed = node.onchainPayment().listSpendableOutputs().filter { it.outpoint.txid in confirmedTxids }
            val confirmedTotal = confirmed.fold(0uL) { total, coin -> total.safe() + coin.valueSats.safe() }
            PrimaryWalletSnapshot(
                walletIndex = walletIndex,
                primary = primary,
                isConnected = node.listPeers().any { it.nodeId == primary.nodeId && it.isConnected },
                confirmedSats = confirmedTotal.safe() - node.listBalances().totalAnchorChannelsReserveSats.safe(),
                confirmedInputCount = confirmed.size,
                fundingFeeRate = node.estimatedFeeRates().channelFundingSatPerVbyte,
                channels = node.listChannels().filter { it.counterpartyNodeId == primary.nodeId },
                hasPendingFunding = node.hasPendingFundingOperation(),
            )
        }
    }

    suspend fun failureAcknowledgementState(walletIndex: Int): PrimaryFailureAcknowledgementState =
        lightningService.callOnchainSend {
            val node = checkedNode(walletIndex)
            if (nativeInstance !== node) {
                nativeInstance = node
                nativeInstanceId = UUID.randomUUID().toString()
            }
            PrimaryFailureAcknowledgementState(
                nodeInstanceId = requireNotNull(nativeInstanceId),
                pendingEventFingerprint = node.nextEvent()?.let(::primaryFailureEventFingerprint),
            )
        }

    suspend fun dispatch(operation: PrimaryWalletOperation, primary: PrimaryNode): String {
        val connection = if (operation.kind == PrimaryWalletOperationKind.Open) {
            runSuspendCatching {
                primary.copy(address = lightningService.primaryPeerAddress(primary))
            }.getOrElse {
                throw PrimaryWalletDispatchRejected("The primary node connection could not be prepared.", it)
            }
        } else {
            primary
        }
        return lightningService.callOnchainSend {
            val node = checkedNode(operation.walletIndex)
            if (node.hasPendingFundingOperation()) {
                throw PrimaryWalletDispatchRejected("A channel funding transaction is already pending.")
            }
            runCatching { dispatch(node, operation, connection) }.getOrElse { error ->
                when (error) {
                    is NodeException.ChannelCreationFailed,
                    is NodeException.ChannelSplicingFailed,
                    is NodeException.InsufficientFunds,
                    is NodeException.ConnectionFailed,
                    is NodeException.InvalidAddress,
                    is NodeException.InvalidAmount,
                    is NodeException.InvalidFeeRate,
                    is NodeException.NotRunning,
                    -> throw PrimaryWalletDispatchRejected("The primary node could not start this transaction.", error)
                    else -> throw error
                }
            }
        }
    }

    private fun dispatch(node: Node, operation: PrimaryWalletOperation, primary: PrimaryNode): String =
        when (operation.kind) {
            PrimaryWalletOperationKind.Open -> node.openChannelWithUserChannelId(
                primary.nodeId,
                requireNotNull(primary.address),
                operation.amountSats,
                null,
                null,
                requireNotNull(operation.userChannelId),
            )
            PrimaryWalletOperationKind.SpliceIn -> requireNotNull(operation.userChannelId).also {
                node.spliceIn(it, operation.primaryNodeId, operation.amountSats)
            }
            PrimaryWalletOperationKind.SpliceOut -> requireNotNull(operation.userChannelId).also {
                node.spliceOutWithFeeRate(
                    it,
                    operation.primaryNodeId,
                    requireNotNull(operation.address),
                    operation.amountSats,
                    operation.feeRate,
                )
            }
        }

    suspend fun matchesPayment(operation: PrimaryWalletOperation): Boolean = lightningService.callOnchainSend {
        val txid = operation.fundingTxid ?: return@callOnchainSend false
        val details = checkedNode(operation.walletIndex).transactionDetails(txid) ?: return@callOnchainSend false
        details.outputs.any {
            it.scriptpubkeyAddress == operation.address && it.value > 0L && it.value.toULong() == operation.amountSats
        }
    }

    suspend fun paymentFee(operation: PrimaryWalletOperation): ULong? = lightningService.callOnchainSend {
        val txid = operation.fundingTxid ?: return@callOnchainSend null
        val node = checkedNode(operation.walletIndex)
        val details = node.transactionDetails(txid) ?: return@callOnchainSend null
        exactOriginalSendFee(details, details.inputs.map { OnchainSendInput(it.txid, it.vout) }) {
            node.transactionDetails(it)
        }
    }

    suspend fun verifiedPaymentConfirmation(activity: OnchainActivity): ConfirmationStatus? =
        lightningService.callOnchainSend {
            if (activity.walletId != WalletScope.default ||
                activity.txType != PaymentType.SENT || activity.isTransfer
            ) {
                return@callOnchainSend null
            }
            val node = checkedNode(walletIndex)
            val details = node.transactionDetails(activity.txId) ?: return@callOnchainSend null
            if (!details.outputs.any {
                    it.scriptpubkeyAddress == activity.address && it.value > 0L &&
                        it.value.toULong() == activity.value
                }
            ) {
                return@callOnchainSend null
            }
            val fee = exactOriginalSendFee(details, details.inputs.map { OnchainSendInput(it.txid, it.vout) }) {
                node.transactionDetails(it)
            }
            if (fee == null || fee != activity.fee) return@callOnchainSend null
            node.fetchTransactionStatus(activity.txId)
        }

    private fun Node.transactionDetails(txid: String): TransactionDetails? =
        getTransactionDetails(txid) ?: runCatching { fetchTransactionDetails(txid) }.getOrNull()

    private fun checkedNode(walletIndex: Int): Node {
        if (walletIndex != lightningService.currentWalletIndex) throw ServiceError.NodeNotSetup()
        return lightningService.node ?: throw ServiceError.NodeNotStarted()
    }
}
