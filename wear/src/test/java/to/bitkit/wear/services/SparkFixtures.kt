package to.bitkit.wear.services

import breez_sdk_spark.BitcoinNetwork
import breez_sdk_spark.Bolt11Invoice
import breez_sdk_spark.Bolt11InvoiceDetails
import breez_sdk_spark.Bolt11RouteHint
import breez_sdk_spark.Bolt11RouteHintHop
import breez_sdk_spark.GetInfoResponse
import breez_sdk_spark.InputType
import breez_sdk_spark.Payment
import breez_sdk_spark.PaymentDetails
import breez_sdk_spark.PaymentMethod
import breez_sdk_spark.PaymentRequestSource
import breez_sdk_spark.PaymentStatus
import breez_sdk_spark.PaymentType
import breez_sdk_spark.SparkAddressDetails
import breez_sdk_spark.SparkHtlcDetails
import breez_sdk_spark.SparkHtlcStatus
import java.math.BigInteger

/**
 * What Breez SDK 0.28.3 printed on the local regtest Spark stack for one wallet's invoices and Spark address
 * (2026-11-27). [IDENTITY] is the wallet's `getInfo().identityPubkey`. An invoice made with Bitkit's settings names
 * only [PROVIDER_NODE], Spark's Lightning node, as payee and [PROVIDER_HINT], a channel of that node. With the SDK's
 * `preferSparkOverLightning` on, the invoice also carries [SPARK_ADDRESS_HINT], whose node is the identity.
 */
object SparkFixtures {
    const val IDENTITY = "02698b27ac308b275671b3ca25436346469d04a5bba578ae39feba1d65897a6abc"
    const val OTHER_IDENTITY = "03a1b2c3d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff"
    const val PROVIDER_NODE = "0313ee54b3e35eed377248c83e77004972a3807af648a22ea1e8a9d22ef8b9a11c"
    const val SPARK_ADDRESS = "sparkrt1pgssy6vty7krpze82ecm8j39gd35v35aqjjmhftc4culawsavkyh564u2uv8v0"
    const val PAYMENT_HASH = "0a00a78ffbde5c36ad8220a26bb2609347bbf7e7c195622c42b81f088ccf1f6e"
    const val TIMESTAMP = 1_795_827_219uL
    const val EXPIRY = 3_600uL

    val PROVIDER_HINT = hint("026ccad2762df4865753f7adc464acdbb9ae4509d0f2109789b91941cfe0e67261", "1x1026x2")
    val SPARK_ADDRESS_HINT = hint(IDENTITY, "16000000x16000000x1")

    fun hint(srcNodeId: String, shortChannelId: String) = Bolt11RouteHint(
        listOf(
            Bolt11RouteHintHop(
                srcNodeId = srcNodeId,
                shortChannelId = shortChannelId,
                feesBaseMsat = 0u,
                feesProportionalMillionths = 0u,
                cltvExpiryDelta = 0u,
                htlcMinimumMsat = null,
                htlcMaximumMsat = null,
            ),
        ),
    )

    /** An invoice as Bitkit creates it, unless other [hints] are given. */
    fun invoice(
        bolt11: String = "lnbcrt1invoice",
        paymentHash: String = PAYMENT_HASH,
        amountMsat: ULong? = null,
        hints: List<Bolt11RouteHint> = listOf(PROVIDER_HINT),
    ) = Bolt11InvoiceDetails(
        amountMsat = amountMsat,
        description = "",
        descriptionHash = null,
        expiry = EXPIRY,
        invoice = Bolt11Invoice(bolt11, PaymentRequestSource(null, null)),
        minFinalCltvExpiryDelta = 60uL,
        network = BitcoinNetwork.REGTEST,
        payeePubkey = PROVIDER_NODE,
        paymentHash = paymentHash,
        paymentSecret = "secret",
        routingHints = hints,
        timestamp = TIMESTAMP,
    )

    fun invoiceInput(
        bolt11: String = "lnbcrt1invoice",
        paymentHash: String = PAYMENT_HASH,
        hints: List<Bolt11RouteHint> = listOf(PROVIDER_HINT),
    ): InputType = InputType.Bolt11Invoice(invoice(bolt11, paymentHash, hints = hints))

    fun sparkAddressInput(identity: String = IDENTITY, address: String = SPARK_ADDRESS): InputType =
        InputType.SparkAddress(
            SparkAddressDetails(address, identity, BitcoinNetwork.REGTEST, PaymentRequestSource(null, null)),
        )

    fun info(balanceSats: ULong, identityPubkey: String = IDENTITY) = GetInfoResponse(
        identityPubkey = identityPubkey,
        balanceSats = balanceSats,
        tokenBalances = emptyMap(),
        recoverableFundsSats = 0uL,
        lastSyncError = null,
        lastSyncedAt = 1uL,
    )

    fun payment(
        id: String,
        status: PaymentStatus,
        type: PaymentType = PaymentType.SEND,
        paymentHash: String? = PAYMENT_HASH,
        amountSats: Long = 1_000,
        feeSats: Long = 3,
        timestamp: ULong = TIMESTAMP,
    ) = Payment(
        id = id,
        paymentType = type,
        status = status,
        amount = BigInteger.valueOf(amountSats),
        fees = BigInteger.valueOf(feeSats),
        timestamp = timestamp,
        method = PaymentMethod.LIGHTNING,
        details = paymentHash?.let {
            PaymentDetails.Lightning(
                description = null,
                invoice = "lnbcrt1invoice",
                destinationPubkey = PROVIDER_NODE,
                htlcDetails = SparkHtlcDetails(it, null, TIMESTAMP + EXPIRY, SparkHtlcStatus.WAITING_FOR_PREIMAGE),
                lnurlPayInfo = null,
                lnurlWithdrawInfo = null,
                lnurlReceiveMetadata = null,
                conversionInfo = null,
            )
        },
        conversionDetails = null,
    )
}
