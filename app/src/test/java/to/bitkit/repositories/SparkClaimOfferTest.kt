package to.bitkit.repositories

import breez_sdk_spark.ClaimDeferredReason
import breez_sdk_spark.ClaimDepositOutcome
import breez_sdk_spark.ClaimDepositQuote
import breez_sdk_spark.FetchClaimDepositQuoteResponse
import org.junit.Test
import kotlin.test.assertEquals

class SparkClaimOfferTest {

    @Test
    fun `offers only the standard claim when no early claim is quoted`() {
        val offer = SparkClaimOffer.from(TXID, 1u, quote(instant = null))

        assertEquals(SparkClaimOffer.Standard(TXID, 1u, feeSats = 907uL, confirmations = 3u), offer)
    }

    @Test
    fun `leaves an early claim within the cap to the sdk`() {
        val offer = SparkClaimOffer.from(TXID, 1u, quote(instant = early(feeSats = 900uL, maxFeeSats = 1_000uL)))

        assertEquals(SparkClaimOffer.Automatic(TXID, 1u, feeSats = 900uL, confirmations = 0u), offer)
    }

    @Test
    fun `offers a choice when the early claim costs more than the cap`() {
        val offer = SparkClaimOffer.from(
            TXID,
            1u,
            quote(instant = early(feeSats = 1_350uL, maxFeeSats = 1_000uL, confirmations = 1u)),
        )

        assertEquals(
            SparkClaimOffer.Choice(
                txid = TXID,
                vout = 1u,
                earlyFeeSats = 1_350uL,
                earlyConfirmations = 1u,
                standardFeeSats = 907uL,
                standardConfirmations = 3u,
            ),
            offer,
        )
    }

    @Test
    fun `offers a choice when automatic claiming is off`() {
        val offer = SparkClaimOffer.from(TXID, 1u, quote(instant = early(feeSats = 900uL, maxFeeSats = null)))

        assertEquals(SparkClaimOffer.Choice(TXID, 1u, 900uL, 0u, 907uL, 3u), offer)
    }

    @Test
    fun `maps each claim outcome`() {
        assertEquals(SparkEarlyClaim.Claimed, SparkEarlyClaim.from(ClaimDepositOutcome.Submitted))
        assertEquals(
            SparkEarlyClaim.Waiting,
            SparkEarlyClaim.from(ClaimDepositOutcome.Deferred(ClaimDeferredReason.NoEarlyClaimAvailable)),
        )
        assertEquals(
            SparkEarlyClaim.Repriced(1_500uL),
            SparkEarlyClaim.from(ClaimDepositOutcome.Deferred(ClaimDeferredReason.MaxFeeExceeded(1_500uL, 1_350uL))),
        )
        assertEquals(
            SparkEarlyClaim.Declined,
            SparkEarlyClaim.from(ClaimDepositOutcome.Deferred(ClaimDeferredReason.ProviderDeclined("busy"))),
        )
    }

    private fun quote(instant: ClaimDepositQuote?) = FetchClaimDepositQuoteResponse(
        amountSats = 15_000uL,
        confirmations = 0u,
        instant = instant,
        mature = ClaimDepositQuote(
            confirmationsRequired = 3u,
            creditAmountSats = 14_093uL,
            feeSats = 907uL,
            maxFeeSats = 1_000uL,
            isEstimate = false,
        ),
    )

    private fun early(feeSats: ULong, maxFeeSats: ULong?, confirmations: UInt = 0u) = ClaimDepositQuote(
        confirmationsRequired = confirmations,
        creditAmountSats = 15_000uL - feeSats,
        feeSats = feeSats,
        maxFeeSats = maxFeeSats,
        isEstimate = false,
    )

    private companion object {
        const val TXID = "txid"
    }
}
