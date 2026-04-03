package to.bitkit.ext

import com.synonym.bitkitcore.LnurlPayData
import com.synonym.bitkitcore.LnurlWithdrawData
import org.junit.Test
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

class LnurlExtTest : BaseUnitTest() {

    @Test
    fun `minSendableSat rounds up when msats not divisible by 1000`() {
        val data = LnurlPayData(
            uri = "lnurl",
            callback = "callback",
            minSendable = 100_500u,
            maxSendable = 200_000u,
            metadataStr = "[]",
            commentAllowed = null,
            allowsNostr = false,
            nostrPubkey = null,
        )

        assertEquals(101u, data.minSendableSat())
    }

    @Test
    fun `minSendableSat keeps exact sat amounts`() {
        val data = LnurlPayData(
            uri = "lnurl",
            callback = "callback",
            minSendable = 100_000u,
            maxSendable = 200_000u,
            metadataStr = "[]",
            commentAllowed = null,
            allowsNostr = false,
            nostrPubkey = null,
        )

        assertEquals(100u, data.minSendableSat())
        assertEquals(0u, data.copy(minSendable = 0u).minSendableSat())
    }

    @Test
    fun `maxSendableSat floors when msats not divisible by 1000`() {
        val data = LnurlPayData(
            uri = "lnurl",
            callback = "callback",
            minSendable = 1_000u,
            maxSendable = 100_999u,
            metadataStr = "[]",
            commentAllowed = null,
            allowsNostr = false,
            nostrPubkey = null,
        )

        assertEquals(100u, data.maxSendableSat())
        assertEquals(0u, data.copy(maxSendable = 0u).maxSendableSat())
    }

    @Test
    fun `minWithdrawableSat rounds up and treats null as zero`() {
        val nullMin = LnurlWithdrawData(
            uri = "lnurl",
            callback = "callback",
            k1 = "k1",
            defaultDescription = "desc",
            minWithdrawable = null,
            maxWithdrawable = 1_000u,
            tag = "withdraw",
        )
        assertEquals(0u, nullMin.minWithdrawableSat())

        val nonRoundMin = nullMin.copy(minWithdrawable = 1_500u)
        assertEquals(2u, nonRoundMin.minWithdrawableSat())
    }

    @Test
    fun `isFixedAmount returns true when min equals max`() {
        val data = lnurlPayData(minSendable = 5_000u, maxSendable = 5_000u)
        assertEquals(true, data.isFixedAmount())
    }

    @Test
    fun `isFixedAmount returns true for sub-sat fixed amount`() {
        val data = lnurlPayData(minSendable = 500_500u, maxSendable = 500_500u)
        assertEquals(501u, data.minSendableSat())
        assertEquals(500u, data.maxSendableSat())
        assertEquals(true, data.isFixedAmount())
    }

    @Test
    fun `isFixedAmount returns false for variable range`() {
        val data = lnurlPayData(minSendable = 1_000u, maxSendable = 100_000u)
        assertEquals(false, data.isFixedAmount())
    }

    @Test
    fun `callbackAmountMsats returns original msats for fixed amount`() {
        val data = lnurlPayData(minSendable = 500_500u, maxSendable = 500_500u)
        assertEquals(500_500u, data.callbackAmountMsats(500u))
    }

    @Test
    fun `callbackAmountMsats converts user sats for variable amount`() {
        val data = lnurlPayData(minSendable = 1_000u, maxSendable = 100_000u)
        assertEquals(50_000u, data.callbackAmountMsats(50u))
    }

    @Test
    fun `withdraw isFixedAmount returns true for sub-sat fixed amount`() {
        val data = withdrawData(minWithdrawable = 500_500u, maxWithdrawable = 500_500u)
        assertEquals(true, data.isFixedAmount())
    }

    @Test
    fun `withdraw isFixedAmount returns false for variable range`() {
        val data = withdrawData(minWithdrawable = 1_000u, maxWithdrawable = 100_000u)
        assertEquals(false, data.isFixedAmount())
    }

    private fun lnurlPayData(
        minSendable: ULong = 1_000u,
        maxSendable: ULong = 100_000u,
    ) = LnurlPayData(
        uri = "lnurl",
        callback = "callback",
        minSendable = minSendable,
        maxSendable = maxSendable,
        metadataStr = "[]",
        commentAllowed = null,
        allowsNostr = false,
        nostrPubkey = null,
    )

    private fun withdrawData(
        minWithdrawable: ULong? = null,
        maxWithdrawable: ULong = 1_000u,
    ) = LnurlWithdrawData(
        uri = "lnurl",
        callback = "callback",
        k1 = "k1",
        defaultDescription = "desc",
        minWithdrawable = minWithdrawable,
        maxWithdrawable = maxWithdrawable,
        tag = "withdraw",
    )
}
