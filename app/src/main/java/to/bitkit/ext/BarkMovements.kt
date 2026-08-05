package to.bitkit.ext

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import to.bitkit.models.WalletScope
import uniffi.bark.Movement
import java.time.Instant
import kotlin.math.absoluteValue

/** Prefix keeping bark movement ids from colliding with ldk-node payment ids in the same store. */
const val BARK_ACTIVITY_ID_PREFIX = "bark:"

/**
 * Maps a bark [Movement] onto the bitkit-core [Activity] model so Ark payments show up in the
 * existing activity list, detail screen, tags and contacts with no UI changes.
 *
 * bark reports balance deltas as signed sats: negative is outgoing. The FFI `Movement` carries no
 * preimage, so [LightningActivity.preimage] stays null.
 */
fun Movement.toActivity(walletId: String = WalletScope.default): Activity {
    val createdAtSecs = parseBarkTimestamp(createdAt)
    val updatedAtSecs = parseBarkTimestamp(updatedAt)

    return Activity.Lightning(
        LightningActivity.create(
            walletId = walletId,
            id = "$BARK_ACTIVITY_ID_PREFIX$id",
            txType = if (intendedBalanceSats < 0) PaymentType.SENT else PaymentType.RECEIVED,
            status = status.toPaymentState(),
            value = effectiveBalanceSats.absoluteValue.toULong(),
            fee = offchainFeeSats,
            invoice = lightningInvoice ?: lightningOffer ?: sentToAddresses.firstOrNull().orEmpty(),
            message = "$subsystemName/$subsystemKind",
            timestamp = createdAtSecs,
            createdAt = createdAtSecs,
            updatedAt = updatedAtSecs,
        )
    )
}

private fun String.toPaymentState(): PaymentState = when (this) {
    "successful" -> PaymentState.SUCCEEDED
    "failed", "canceled" -> PaymentState.FAILED
    else -> PaymentState.PENDING
}

/**
 * bark emits RFC 3339 timestamps. A malformed value must not drop the whole activity, so it falls
 * back to the epoch and the movement still shows up.
 */
private fun parseBarkTimestamp(value: String): ULong =
    runCatching { Instant.parse(value).epochSecond.toULong() }.getOrDefault(0uL)
