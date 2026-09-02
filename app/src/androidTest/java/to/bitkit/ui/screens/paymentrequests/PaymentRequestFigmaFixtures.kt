package to.bitkit.ui.screens.paymentrequests

import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestTarget
import kotlin.time.Instant

/** Figma frames 48185:303457, 48185:303440, 48185:303376. */
internal object PaymentRequestFigmaFixtures {
    val alex = profile("pubkyyrsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Alex Stronghand")
    val anna = profile("pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Anna Pleb")
    val areem = profile("pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Areem Holden")
    val craig = profile("pubkybrsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Craig Wrong")
    val john = profile("pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "John Carvalho")
    val paola = profile("pubkynrsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Paola Andina")
    val ben = profile("pubky8rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Ben")

    val recipientContacts = persistentListOf(alex, anna, areem, craig, john)
    val recipientInvoiceContacts = persistentListOf(alex, anna, areem, craig, john, paola)
    val listContacts = persistentListOf(areem, ben, anna, john)

    val recipientTargets = persistentListOf(
        target(alex),
        target(anna),
        target(areem),
        target(craig),
        target(john),
    )
    val recipientInvoiceTargets = persistentListOf(
        target(alex),
        target(anna),
        target(areem),
        target(craig),
        target(john),
        target(paola),
    )

    fun listRequests(now: Instant) = persistentListOf(
        request("incoming-areem", areem.publicKey, 21_000uL, "Lunch last week", now),
        request("incoming-ben", ben.publicKey, 100_000uL, "Groceries", now),
        request(
            id = "outgoing-anna",
            counterparty = anna.publicKey,
            amountSats = 14_500uL,
            note = "Snacks at conference",
            createdAt = now,
            direction = PaykitPaymentRequestDirection.Outgoing,
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
        ),
        request(
            id = "outgoing-john",
            counterparty = john.publicKey,
            amountSats = 50_000uL,
            note = "Steaks & Burgers",
            createdAt = now,
            direction = PaykitPaymentRequestDirection.Outgoing,
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
        ),
    )

    fun listPending(now: Instant) = persistentListOf(
        request("incoming-areem", areem.publicKey, 21_000uL, "Lunch last week", now),
        request("incoming-ben", ben.publicKey, 100_000uL, "Groceries", now),
    )

    fun target(profile: PubkyProfile) = PaykitPaymentRequestTarget(
        publicKey = profile.publicKey,
        receiverPath = "bitkit/wallet",
    )

    fun request(
        id: String,
        counterparty: String,
        amountSats: ULong,
        note: String,
        createdAt: Instant,
        direction: PaykitPaymentRequestDirection = PaykitPaymentRequestDirection.Incoming,
        deliveryStatus: PaykitPaymentRequestDeliveryStatus? = null,
    ) = PaykitPaymentRequest(
        paymentRequestId = id,
        counterparty = counterparty,
        counterpartyReceiverPath = "bitkit/wallet",
        amountValue = "0",
        amountSats = amountSats,
        note = note,
        createdAt = createdAt,
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
        deliveryStatus = deliveryStatus,
        direction = direction,
        lifecycleState = PaymentRequestLifecycleState.PROPOSED,
    )

    private fun profile(publicKey: String, name: String) =
        PubkyProfile.forDisplay(publicKey, name, null)
}
