package to.bitkit.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamRockSetupRequestTest {
    @Test
    fun `parse accepts modern setup URL`() {
        val setup = assertNotNull(
            SamRockSetupRequest.parse(
                "https://btcpay.example.com/plugins/store-1/samrock/protocol?setup=btc-chain,btc-ln&otp=secret"
            )
        )

        assertEquals("store-1", setup.storeId)
        assertEquals("secret", setup.otp)
        assertEquals("btcpay.example.com", setup.hostDisplayName)
        assertEquals(
            "https://btcpay.example.com/plugins/store-1/samrock/protocol?setup=btc-chain%2Cbtc-ln&otp=secret",
            setup.postUrl,
        )
        assertTrue(setup.requestsBitcoinOnchain)
        assertTrue(setup.requestsUnsupportedMethods)
    }

    @Test
    fun `parse defaults missing setup to all`() {
        val setup = assertNotNull(
            SamRockSetupRequest.parse(
                "https://btcpay.example.com/plugins/store/samrock/protocol?otp=secret&ignored=true"
            )
        )

        assertEquals(setOf(SamRockPaymentMethod.ALL), setup.requestedMethods)
        assertEquals(
            "https://btcpay.example.com/plugins/store/samrock/protocol?otp=secret",
            setup.postUrl,
        )
        assertTrue(setup.requestsBitcoinOnchain)
        assertTrue(setup.requestsUnsupportedMethods)
    }

    @Test
    fun `parse does not default unknown setup methods`() {
        val setup = assertNotNull(
            SamRockSetupRequest.parse(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=custom&otp=secret"
            )
        )

        assertTrue(setup.requestedMethods.isEmpty())
        assertTrue(setup.hasUnknownMethods)
        assertFalse(setup.requestsBitcoinOnchain)
        assertTrue(setup.requestsUnsupportedMethods)
    }

    @Test
    fun `parse flags unknown methods alongside bitcoin onchain`() {
        val setup = assertNotNull(
            SamRockSetupRequest.parse(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain,custom&otp=secret"
            )
        )

        assertEquals(setOf(SamRockPaymentMethod.BTC_ONCHAIN), setup.requestedMethods)
        assertTrue(setup.hasUnknownMethods)
        assertTrue(setup.requestsBitcoinOnchain)
        assertTrue(setup.requestsUnsupportedMethods)
    }

    @Test
    fun `parse recognizes lightning only as unsupported for this flow`() {
        val setup = assertNotNull(
            SamRockSetupRequest.parse(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-ln&otp=secret"
            )
        )

        assertEquals(setOf(SamRockPaymentMethod.BTC_LIGHTNING), setup.requestedMethods)
        assertFalse(setup.requestsBitcoinOnchain)
        assertTrue(setup.requestsUnsupportedMethods)
    }

    @Test
    fun `parse recognizes every setup method value`() {
        val cases = listOf(
            "all" to SamRockPaymentMethod.ALL,
            "btc" to SamRockPaymentMethod.BTC,
            "btc-chain" to SamRockPaymentMethod.BTC_ONCHAIN,
            "lbtc" to SamRockPaymentMethod.LIQUID,
            "liquid-chain" to SamRockPaymentMethod.LIQUID_ONCHAIN,
            "btcln" to SamRockPaymentMethod.BTC_LIGHTNING,
            "btc-ln" to SamRockPaymentMethod.BTC_LIGHTNING,
        )

        cases.forEach { (setupValue, method) ->
            val setup = assertNotNull(
                SamRockSetupRequest.parse(
                    "https://btcpay.example.com/plugins/store/samrock/protocol?setup=$setupValue&otp=secret"
                ),
                setupValue,
            )

            assertEquals(setOf(method), setup.requestedMethods, setupValue)
        }
    }

    @Test
    fun `parse preserves encoded query values in post URL`() {
        val setup = assertNotNull(
            SamRockSetupRequest.parse(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=a%2Bb%20c%26d%3De"
            )
        )

        assertEquals("a+b c&d=e", setup.otp)
        assertEquals(
            "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=a%2Bb%20c%26d%3De",
            setup.postUrl,
        )
    }

    @Test
    fun `parse rejects public http`() {
        assertNull(
            SamRockSetupRequest.parse(
                "http://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
            )
        )
        assertTrue(
            SamRockSetupRequest.isPublicHttpProtocolUrl(
                "http://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
            )
        )
    }

    @Test
    fun `parse allows local and private http`() {
        val urls = listOf(
            "http://localhost/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://127.0.0.1/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://192.168.1.20/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://172.16.1.20/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://10.0.2.2/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://[::1]/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://[fc00::1]/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://[fd00::1]/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://[fe80::1]/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://merchant.local/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
        )

        urls.forEach {
            assertNotNull(SamRockSetupRequest.parse(it), it)
        }
    }

    @Test
    fun `parse rejects public and invalid http lookalikes`() {
        val urls = listOf(
            "http://fc.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://fd.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://172.15.1.20/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://172.32.1.20/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://192.167.1.20/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://8.8.8.8/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://10.0.0.999/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
            "http://[2001:db8::1]/plugins/store/samrock/protocol?setup=btc-chain&otp=secret",
        )

        urls.forEach {
            assertNull(SamRockSetupRequest.parse(it), it)
        }
    }

    @Test
    fun `parse rejects non samrock URLs`() {
        assertNull(SamRockSetupRequest.parse("https://btcpay.example.com/plugins/store/other/protocol?otp=secret"))
        assertNull(SamRockSetupRequest.parse("https://btcpay.example.com/plugins/store/samrock?otp=secret"))
        assertNull(SamRockSetupRequest.parse("https://btcpay.example.com/plugins/store/samrock/protocol"))
        assertNull(SamRockSetupRequest.parse("https://btcpay.example.com/plugins/store/samrock/protocol?otp="))
        assertNull(
            SamRockSetupRequest.parse(
                "https://user:pass@btcpay.example.com/plugins/store/samrock/protocol?otp=secret"
            )
        )
    }

    @Test
    fun `parse rejects malformed percent escapes`() {
        assertNull(SamRockSetupRequest.parse("https://btcpay.example.com/plugins/%zz/samrock/protocol?otp=secret"))
        assertNull(SamRockSetupRequest.parse("https://btcpay.example.com/plugins/store/samrock/protocol?otp=%zz"))
    }

    @Test
    fun `sanitized description strips query and fragment`() {
        assertEquals(
            "https://btcpay.example.com/plugins/store/samrock/protocol",
            SamRockSetupRequest.sanitizedDescription(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret#frag"
            ),
        )
    }

    @Test
    fun `sanitized QR log value redacts SamRock query values`() {
        val result = "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
            .sanitizedQrLogValue()

        assertEquals("https://btcpay.example.com/plugins/store/samrock/protocol", result)
        assertFalse(result.contains("otp"))
        assertFalse(result.contains("secret"))
    }

    @Test
    fun `sanitized QR log value redacts malformed SamRock-looking query values`() {
        val result = "https://btcpay.example.com/plugins/%zz/samrock/protocol?setup=btc-chain&otp=secret"
            .sanitizedQrLogValue()

        assertEquals("https://btcpay.example.com/plugins/%zz/samrock/protocol", result)
        assertFalse(result.contains("otp"))
        assertFalse(result.contains("secret"))
    }

    @Test
    fun `sanitized QR log value redacts ordinary QR values`() {
        val result = "bitcoin:bcrt1qexample?amount=1".sanitizedQrLogValue()

        assertTrue(result.startsWith("redacted#"))
        assertFalse(result.contains("bcrt1qexample"))
        assertFalse(result.contains("amount"))
    }

    @Test
    fun `sanitized deeplink log value strips sensitive URL parts`() {
        assertEquals(
            "https://btcpay.example.com/plugins/store/samrock/protocol",
            "https://user:pass@btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
                .sanitizedDeeplinkLogValue(),
        )
        assertEquals(
            "https://example.com/path",
            "https://user:pass@example.com/path?token=secret#fragment".sanitizedDeeplinkLogValue(),
        )
        assertEquals("bitcoin", "bitcoin:bcrt1qexample?amount=1".sanitizedDeeplinkLogValue())
        assertEquals(
            "bitkit://pubky-auth/success",
            "bitkit://pubky-auth/success?nonce=secret".sanitizedDeeplinkLogValue(),
        )
    }

    @Test
    fun `sanitized launch key keeps setup links distinct without exposing query`() {
        val first = assertNotNull(
            SamRockSetupRequest.sanitizedLaunchKey(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
            )
        )
        val second = assertNotNull(
            SamRockSetupRequest.sanitizedLaunchKey(
                "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=other"
            )
        )

        assertTrue(first.startsWith("https://btcpay.example.com/plugins/store/samrock/protocol#"))
        assertFalse(first.contains("otp"))
        assertFalse(first.contains("secret"))
        assertFalse(first.contains("setup"))
        assertFalse(first == second)
    }

    @Test
    fun `sanitized description drops userinfo and malformed paths`() {
        assertEquals(
            "https://btcpay.example.com/plugins/store/samrock/protocol",
            SamRockSetupRequest.sanitizedDescription(
                "https://user:pass@btcpay.example.com/plugins/store/samrock/protocol?otp=secret"
            ),
        )
        assertEquals(
            "https://btcpay.example.com/plugins/%zz/samrock/protocol",
            SamRockSetupRequest.sanitizedDescription(
                "https://btcpay.example.com/plugins/%zz/samrock/protocol?otp=secret"
            ),
        )
        assertEquals(
            "https://btcpay.example.com/plugins/store/samrock/protocol",
            SamRockSetupRequest.sanitizedDescription(
                "https://btcpay.example.com/plugins/store/samrock/protocol?otp=%zz"
            ),
        )
    }
}
