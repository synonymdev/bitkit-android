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
}
