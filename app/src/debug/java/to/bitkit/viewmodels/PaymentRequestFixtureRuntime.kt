package to.bitkit.viewmodels

import android.net.Uri
import com.synonym.paykit.PaymentRequestLifecycleState
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.ui.utils.ScreenDeepLinks
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Figma frames 48185:303457, 48185:303440, 48185:303376. */
internal object PaymentRequestFixtureRuntime {
    private const val HOST = "dev-fixture"
    private const val PATH = "payment-request"
    private const val RECEIVER_PATH = "bitkit/wallet"
    private const val BOLT11 = "btc-lightning-bolt11"

    private val alex = profile("pubkyyrsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Alex Stronghand")
    private val anna = profile("pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Anna Pleb")
    private val areem = profile("pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Areem Holden")
    private val craig = profile("pubkybrsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Craig Wrong")
    private val john = profile("pubky5rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "John Carvalho")
    private val paola = profile("pubkynrsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Paola Andina")
    private val ben = profile("pubky8rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg", "Ben")

    fun fixtureFor(uri: Uri): PaymentRequestFixture? {
        if (uri.scheme?.lowercase() != ScreenDeepLinks.SCHEME) return null
        if (uri.host?.lowercase() != HOST) return null
        if (uri.pathSegments.singleOrNull()?.lowercase() != PATH) return null

        val now = Clock.System.now()
        return PaymentRequestFixture(
            contacts = listOf(alex, anna, areem, ben, craig, john, paola),
            targets = listOf(alex, anna, areem, craig, john, paola).map(::target),
            pending = listOf(
                request("incoming-areem", areem, 21_000uL, "Lunch last week", now),
                request("incoming-ben", ben, 100_000uL, "Groceries", now),
            ),
            history = listOf(
                request("outgoing-anna", anna, 14_500uL, "Snacks at conference", now, isOutgoing = true),
                request("outgoing-john", john, 50_000uL, "Steaks & Burgers", now, isOutgoing = true),
            ),
        )
    }


    private fun target(profile: PubkyProfile) = PaykitPaymentRequestTarget(
        publicKey = profile.publicKey,
        receiverPath = RECEIVER_PATH,
    )

    private fun request(
        id: String,
        counterparty: PubkyProfile,
        amountSats: ULong,
        note: String,
        createdAt: Instant,
        isOutgoing: Boolean = false,
    ) = PaykitPaymentRequest(
        paymentRequestId = id,
        counterparty = counterparty.publicKey,
        counterpartyReceiverPath = RECEIVER_PATH,
        amountValue = amountSats.toString(),
        amountSats = amountSats,
        note = note,
        createdAt = createdAt,
        expiresAt = createdAt + 7.days,
        acceptedPaymentEndpointIdentifiers = listOf(BOLT11),
        deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent.takeIf { isOutgoing },
        direction = if (isOutgoing) PaykitPaymentRequestDirection.Outgoing else PaykitPaymentRequestDirection.Incoming,
        lifecycleState = PaymentRequestLifecycleState.PROPOSED,
    )

    private fun profile(publicKey: String, name: String) = PubkyProfile.forDisplay(publicKey, name, null)
}
