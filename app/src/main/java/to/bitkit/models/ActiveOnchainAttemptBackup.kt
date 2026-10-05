package to.bitkit.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import to.bitkit.repositories.OnchainSendAttempt
import to.bitkit.repositories.OnchainSendEvidence
import to.bitkit.repositories.OnchainSendInput
import to.bitkit.repositories.OnchainTransferContext
import to.bitkit.repositories.PaykitPaymentProofKind
import to.bitkit.repositories.PaykitPaymentRequestId

/** Shared v1: one active operation; integer strings avoid cross-platform precision loss. */
@Serializable
@Suppress("LongParameterList")
data class ActiveOnchainAttemptBackup(
    val version: Int = 1,
    val wallet: Wallet,
    val attemptId: String,
    val requestId: PaykitPaymentRequestId? = null,
    val orderId: String? = null,
    val payerIdentity: String? = null,
    val address: String,
    val amountSats: String,
    val isMaxAmount: Boolean,
    val status: String,
    val txid: String? = null,
    val rejectionReason: String? = null,
    val originalInputs: List<Input>? = null,
    val candidateTxids: List<String> = emptyList(),
    val feeRateSatsPerVByte: String,
    val followup: Followup? = null,
    val transfer: Transfer? = null,
) {
    @Serializable
    data class Wallet(val kind: String = "software", val network: String, val binding: String, val sourceIndex: String)

    @Serializable
    data class Input(val txid: String, val vout: String)

    @Serializable
    data class Followup(
        val feeSats: String,
        val tags: List<String>,
        val contact: JsonElement? = null,
        val createdAtMillis: String,
        val channelId: String? = null,
    )

    @Serializable
    data class Transfer(
        val txTotalSats: String,
        val preTransferOnchainSats: String,
        val originalOrderClientBalanceSats: String,
        val originalOrderFeeSats: String,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun restored(network: String, binding: String, walletId: String, destinationIndex: Int): OnchainSendAttempt {
        require(version == 1 && wallet.kind == "software")
        require(wallet.network in setOf("bitcoin", "testnet", "signet", "regtest") && wallet.network == network)
        require(wallet.binding.matches(HEX) && wallet.binding == binding)
        require(unsigned(wallet.sourceIndex) <= Int.MAX_VALUE.toULong() && destinationIndex >= 0)
        require(attemptId.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        require(address.isNotBlank() && unsigned(amountSats) > 0uL)
        require(requestId == null || orderId == null)
        require(
            requestId == null ||
                (payerIdentity != null && PubkyPublicKeyFormat.normalized(payerIdentity) == payerIdentity)
        )
        require(unsigned(feeRateSatsPerVByte) <= UInt.MAX_VALUE.toULong())
        require(candidateTxids.distinct().size == candidateTxids.size && candidateTxids.all { it.matches(HEX) })
        val inputs = originalInputs?.map { input ->
            require(input.txid.matches(HEX) && unsigned(input.vout) <= UInt.MAX_VALUE.toULong())
            OnchainSendInput(input.txid, unsigned(input.vout).toUInt())
        }
        if (inputs == null) {
            require(candidateTxids.isEmpty() && txid == null && status == "pending")
        } else {
            require(inputs.isNotEmpty() && inputs.distinct().size == inputs.size)
            require(unsigned(feeRateSatsPerVByte) > 0uL)
            require(candidateTxids.isNotEmpty() && txid in candidateTxids)
        }
        val evidence = when (status) {
            "pending" -> OnchainSendEvidence.Pending
            "accepted" -> OnchainSendEvidence.Accepted.also { require(txid != null && txid in candidateTxids) }
            "rejected" -> OnchainSendEvidence.Rejected
            "unknown" -> OnchainSendEvidence.Unknown
            else -> error("Unsupported active operation status")
        }
        followup?.let {
            unsigned(it.feeSats)
            unsigned(it.createdAtMillis)
        }
        val transferContext = transfer?.let {
            require(orderId != null)
            OnchainTransferContext(
                unsigned(it.txTotalSats),
                unsigned(it.preTransferOnchainSats),
                unsigned(it.originalOrderClientBalanceSats),
                unsigned(it.originalOrderFeeSats),
            )
        }
        require(orderId == null || transferContext != null)
        return OnchainSendAttempt(
            walletId = walletId, walletIndex = destinationIndex, attemptId = attemptId,
            requestId = requestId, orderId = orderId, payerIdentity = payerIdentity,
            address = address, amountSats = unsigned(amountSats), isMaxAmount = isMaxAmount,
            feeRateSatsPerVByte = unsigned(feeRateSatsPerVByte), isTransfer = orderId != null,
            channelId = followup?.channelId, tags = followup?.tags.orEmpty(), evidence = evidence,
            txid = txid, refusalReason = rejectionReason, localFollowupComplete = false,
            originalInputs = inputs, candidateTxids = candidateTxids, transferContext = transferContext,
            backupFollowup = followup, restoredFromBackup = true,
        )
    }

    fun validateProofs(proofs: List<PaykitPaymentStateBackup.Proof>, defaultWalletId: String) {
        val originalRequest = requestId ?: return
        val proof = proofs.single { it.requestId == originalRequest && it.identity == payerIdentity }
        check(proof.kind == PaykitPaymentProofKind.Onchain.type && proof.paymentStarted)
        check(proof.onchainWalletId == null || proof.onchainWalletId == defaultWalletId)
        check(proof.onchainAddress == address && proof.onchainAmountSats?.toString() == amountSats)
        check(proof.paymentIdentifier == null || proof.paymentIdentifier in candidateTxids)
        check(
            proof.proofData == null || (proof.proofData == proof.paymentIdentifier && proof.proofData in candidateTxids)
        )
        if (proof.onchainAcceptanceVerified) {
            check(status == "accepted" && txid != null && proof.paymentIdentifier == txid && proof.proofData == txid)
        }
    }

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        private fun unsigned(value: String): ULong {
            require(value.matches(Regex("0|[1-9][0-9]*")))
            return value.toULong()
        }

        fun from(attempt: OnchainSendAttempt, network: String, binding: String): ActiveOnchainAttemptBackup {
            require(attempt.walletId == WalletScope.default)
            val wire = ActiveOnchainAttemptBackup(
                wallet = Wallet(network = network, binding = binding, sourceIndex = attempt.walletIndex.toString()),
                attemptId = attempt.attemptId, requestId = attempt.requestId, orderId = attempt.orderId,
                payerIdentity = attempt.payerIdentity,
                address = attempt.address,
                amountSats = attempt.amountSats.toString(),
                isMaxAmount = attempt.isMaxAmount,
                status = when (attempt.evidence) {
                    OnchainSendEvidence.Pending -> "pending"
                    OnchainSendEvidence.Accepted, OnchainSendEvidence.Observed -> "accepted"
                    OnchainSendEvidence.Rejected -> "rejected"
                    OnchainSendEvidence.Unknown -> "unknown"
                },
                txid = attempt.txid, rejectionReason = attempt.refusalReason,
                originalInputs = attempt.originalInputs?.map { Input(it.txid, it.vout.toString()) },
                candidateTxids = attempt.candidateTxids, feeRateSatsPerVByte = attempt.feeRateSatsPerVByte.toString(),
                followup = attempt.backupFollowup,
                transfer = attempt.transferContext?.let {
                    Transfer(
                        it.txTotalSats.toString(),
                        it.preTransferOnchainSats.toString(),
                        requireNotNull(it.originalOrderClientBalanceSats).toString(),
                        requireNotNull(it.originalOrderFeeSats).toString(),
                    )
                },
            )
            wire.restored(network, binding, attempt.walletId, attempt.walletIndex)
            return wire
        }
    }
}
