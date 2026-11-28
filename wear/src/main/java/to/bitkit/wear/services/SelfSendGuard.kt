package to.bitkit.wear.services

import breez_sdk_spark.InputType
import to.bitkit.wear.data.OwnInvoiceStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells whether a payment input pays this wallet. The phone and the watch share one Spark wallet, so neither may pay
 * a request the other, or itself, created. `PaymentsRepo` asks before every prepare, and a check that fails refuses
 * the send.
 */
interface SelfSendGuard {
    /** Whether [input], read by the SDK's parser, pays this wallet. Throws when the wallet's identity is unknown. */
    suspend fun isOwn(input: InputType): Boolean
}

/**
 * Recognises a request of this wallet in two ways:
 * - by the wallet's Spark identity public key, the one thing both devices share and an input can carry: a Spark
 *   address encodes it, and a BOLT11 invoice carries it as the node of a route hint hop with the short channel id
 *   [SPARK_ADDRESS_HINT_SCID], when the wallet that made it asked Spark to include its Spark address;
 * - by payment hash, for the invoices in [OwnInvoiceStore]: the ones this device created, and a paired device's once
 *   they are shared with it.
 *
 * The identity alone does not recognise Bitkit's invoices: Bitkit creates them without the Spark address hint (the
 * SDK's `preferSparkOverLightning` is off), and such an invoice names only Spark's provider node, which every Spark
 * wallet shares. Verified on the local regtest stack with SDK 0.28.3. So an invoice the phone created is refused on
 * the watch only once its hash reached the watch's [OwnInvoiceStore].
 */
@Singleton
class WalletSelfSendGuard @Inject constructor(
    private val sparkService: WatchSparkService,
    private val ownInvoiceStore: OwnInvoiceStore,
) : SelfSendGuard {
    companion object {
        /**
         * The route hint short channel id under which an invoice carries its Spark address, as the SDK's parser
         * prints it: the phone's `SPARK_ADDRESS_HINT_SCID`, 17592187092992000001, as block x transaction x output.
         */
        const val SPARK_ADDRESS_HINT_SCID = "16000000x16000000x1"

        /** Whether [input] names [identityPubkey], this wallet's Spark identity in hex, as the wallet it pays. */
        fun namesIdentity(input: InputType, identityPubkey: String): Boolean = when (input) {
            is InputType.SparkAddress -> input.v1.identityPublicKey.equals(identityPubkey, ignoreCase = true)
            is InputType.Bolt11Invoice -> input.v1.routingHints.any { hint ->
                hint.hops.any {
                    it.shortChannelId == SPARK_ADDRESS_HINT_SCID &&
                        it.srcNodeId.equals(identityPubkey, ignoreCase = true)
                }
            }
            else -> false
        }
    }

    override suspend fun isOwn(input: InputType): Boolean {
        if (input is InputType.Bolt11Invoice && ownInvoiceStore.contains(input.v1.paymentHash)) return true
        return namesIdentity(input, sparkService.identityPubkey())
    }
}
