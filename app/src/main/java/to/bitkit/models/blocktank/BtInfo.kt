package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class BtInfo(
    /**
     * deprecated: Use the `versions` object instead.
     */
    val version: Int,

    /**
     * Available nodes.
     */
    val nodes: List<LspNode>,

    val options: Options,

    /**
     * SemVer versions of the micro services.
     */
    val versions: Versions,

    val onchain: Onchain,
) {
    @Serializable
    data class Options(
        /**
         * Minimum channel size
         */
        val minChannelSizeSat: Int,

        /**
         * Maximum channel size
         */
        val maxChannelSizeSat: Int,

        /**
         * Minimum channel lease time in weeks.
         */
        val minExpiryWeeks: Int,

        /**
         * Maximum channel lease time in weeks.
         */
        val maxExpiryWeeks: Int,

        /**
         * Minimum payment confirmation for safe payments.
         */
        val minPaymentConfirmations: Int,

        /**
         * Minimum payment confirmations for high value payments.
         */
        val minHighRiskPaymentConfirmations: Int,

        /**
         * Maximum clientBalanceSat that is accepted as 0conf/turbochannel.
         */
        val max0ConfClientBalanceSat: Int,

        /**
         * Maximum clientBalanceSat in general.
         */
        val maxClientBalanceSat: Int,
    )

    @Serializable
    data class Versions(
        /**
         * SemVer versions of the http micro services.
         */
        val http: String,

        /**
         * SemVer versions of the btc micro services.
         */
        val btc: String,

        /**
         * SemVer versions of the ln2 micro services.
         */
        val ln2: String,
    )

    @Serializable
    data class Onchain(
        val network: BitcoinNetworkEnum,
        val feeRates: FeeRates,
    ) {
        @Serializable
        data class FeeRates(
            /**
             * Fast fee in sat/vbyte.
             */
            val fast: Int,

            /**
             * Mid fee in sat/vbyte.
             */
            val mid: Int,

            /**
             * Slow fee in sat/vbyte.
             */
            val slow: Int,
        )
    }
}
