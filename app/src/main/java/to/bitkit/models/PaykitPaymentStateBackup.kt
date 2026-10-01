@file:OptIn(ExperimentalTime::class)

package to.bitkit.models

import kotlinx.serialization.Serializable
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitPaymentProofKind
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitSubscriptionId
import to.bitkit.repositories.PaykitSubscriptionPresentationState
import to.bitkit.repositories.PendingPaykitPaymentProof
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class PaykitPaymentStateBackup(
    val subscriptions: Map<String, Subscription>,
    val pendingProofs: List<Proof>,
) {
    @Serializable
    data class Subscription(
        val acceptances: List<Acceptance>,
        val presentedProposalIds: Set<PaykitSubscriptionId>,
    ) {
        constructor(state: PaykitSubscriptionPresentationState) : this(
            acceptances = state.acceptedAt.map { Acceptance(it.key, it.value.toString()) },
            presentedProposalIds = state.presentedProposalIds,
        )

        fun restored() = PaykitSubscriptionPresentationState(
            acceptedAt = acceptances.associate { it.id to Instant.parse(it.acceptedAt) },
            presentedProposalIds = presentedProposalIds,
        )
    }

    @Serializable
    data class Acceptance(val id: PaykitSubscriptionId, val acceptedAt: String)

    @Serializable
    data class Proof(
        val identity: String,
        val requestId: PaykitPaymentRequestId,
        val paymentEndpointIdentifier: String,
        val kind: String,
        val paymentStarted: Boolean,
        val paymentIdentifier: String? = null,
        val proofData: String? = null,
        val billingPeriod: PaykitBillingPeriod? = null,
        val onchainAddress: String? = null,
        val onchainAmountSats: ULong? = null,
        val onchainWalletId: String? = null,
        val onchainMatchingTransactionIdsBeforeAttempt: Set<String>,
    ) {
        constructor(proof: PendingPaykitPaymentProof) : this(
            identity = proof.identity,
            requestId = proof.requestId,
            paymentEndpointIdentifier = proof.paymentEndpointIdentifier,
            kind = proof.kind.type,
            paymentStarted = proof.paymentStarted,
            paymentIdentifier = proof.paymentIdentifier,
            proofData = proof.proofData,
            billingPeriod = proof.billingPeriod,
            onchainAddress = proof.onchainAddress,
            onchainAmountSats = proof.onchainAmountSats,
            onchainWalletId = proof.onchainWalletId,
            onchainMatchingTransactionIdsBeforeAttempt = proof.onchainMatchingTransactionIdsBeforeAttempt,
        )

        fun restored() = PendingPaykitPaymentProof(
            identity = identity,
            requestId = requestId.copy(billingPeriodStartsAt = billingPeriod?.startsAt?.toString()),
            paymentEndpointIdentifier = paymentEndpointIdentifier,
            kind = requireNotNull(PaykitPaymentProofKind.entries.find { it.type == kind }),
            paymentStarted = paymentStarted,
            paymentIdentifier = paymentIdentifier,
            proofData = proofData,
            billingPeriod = billingPeriod,
            onchainAddress = onchainAddress,
            onchainAmountSats = onchainAmountSats,
            onchainWalletId = onchainWalletId ?: WalletScope.default,
            onchainMatchingTransactionIdsBeforeAttempt = onchainMatchingTransactionIdsBeforeAttempt,
        )
    }
}
