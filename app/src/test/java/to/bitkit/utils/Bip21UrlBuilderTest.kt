package to.bitkit.utils

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import to.bitkit.utils.Bip21Utils.buildBip21Url

@RunWith(JUnit4::class)
class Bip21UrlBuilderTest {

    // Test helper for expected URL without worrying about encoding
    private fun expectedUrl(base: String, vararg params: Pair<String, String>): String {
        val query = params.joinToString("&") { "${it.first}=${it.second.encodeToUrl()}" }
        return if (params.isNotEmpty()) "$base?$query" else base
    }

    @Test
    fun `basic address without parameters`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val expected = "bitcoin:$address?message=Bitkit"
        Assert.assertEquals(expected, buildBip21Url(address))
    }

    @Test
    fun `address with amount in sats`() {
        val address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        val amount = 100000uL // 0.001 BTC
        val expected = "bitcoin:$address?amount=0.001&message=Bitkit"
        Assert.assertEquals(expected, buildBip21Url(address, amount))
    }

    @Test
    fun `amount with exact 1 BTC`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val amount = 100000000uL
        val expected = "bitcoin:$address?amount=1&message=Bitkit"
        Assert.assertEquals(expected, buildBip21Url(address, amount))
    }

    @Test
    fun `amount with fractional sats rounds correctly`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val amount = 12345678uL
        val expected = "bitcoin:$address?amount=0.12345678&message=Bitkit"
        Assert.assertEquals(expected, buildBip21Url(address, amount))
    }

    @Test
    fun `address with label and message`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val expected = expectedUrl(
            "bitcoin:$address",
            "label" to "Donation",
            "message" to "Thanks for your work!"
        )
        Assert.assertEquals(
            expected,
            buildBip21Url(address, label = "Donation", message = "Thanks for your work!")
        )
    }

    @Test
    fun `address with lightning parameter`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val invoice = "lnbc500n1p3k9v3pp5kzmj..."
        val expected = "bitcoin:$address?message=Bitkit&lightning=${invoice.encodeToUrl()}"
        Assert.assertEquals(expected, buildBip21Url(address, lightningInvoice = invoice))
    }

    @Test
    fun `address with all parameters`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val amount = 50000uL
        val label = "Donation"
        val message = "Thanks!"
        val invoice = "lnbc500n1p3k9v3pp5kzmj..."

        val expected = expectedUrl(
            "bitcoin:$address",
            "amount" to "0.0005",
            "label" to label,
            "message" to message
        ) + "&lightning=${invoice.encodeToUrl()}"

        Assert.assertEquals(expected, buildBip21Url(address, amount, label, message, invoice))
    }

    @Test
    fun `special characters in label and message are encoded`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val label = "Coffee & Tea"
        val message = "For Alice's Birthday!"

        val expected = expectedUrl(
            "bitcoin:$address",
            "label" to label,
            "message" to message
        )

        Assert.assertEquals(expected, buildBip21Url(address, label = label, message = message))
    }

    @Test
    fun `zero sats is handled correctly`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val amount = 0uL
        val expected = "bitcoin:$address?amount=0&message=Bitkit"
        Assert.assertEquals(expected, buildBip21Url(address, amount))
    }

    @Test
    fun `maximum ULong value is handled correctly`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val amount = ULong.MAX_VALUE
        val expected = "bitcoin:$address?amount=184467440737.09551615&message=Bitkit"
        Assert.assertEquals(expected, buildBip21Url(address, amount))
    }

    @Test
    fun `lightning parameter without other parameters`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val invoice = "lnbc100n1p3k9v3pp5kzmj..."
        val expected = "bitcoin:$address?message=Bitkit&lightning=${invoice.encodeToUrl()}"
        Assert.assertEquals(expected, buildBip21Url(address, lightningInvoice = invoice))
    }

    @Test
    fun `lightning parameter with existing parameters`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val amount = 10000uL
        val invoice = "lnbc100n1p3k9v3pp5kzmj..."
        val expected = "bitcoin:$address?amount=0.0001&message=Bitkit&lightning=${invoice.encodeToUrl()}"
        Assert.assertEquals(expected, buildBip21Url(address, amount, lightningInvoice = invoice))
    }
}
