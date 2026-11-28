package to.bitkit.wear.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PhoneSettingsParserTest {
    private companion object {
        /** A cut of what bitkit-android writes: pretty-printed, every default encoded, many keys the watch ignores. */
        val ANDROID_BACKUP = """
            {
                "version": 1,
                "createdAt": 1800000000000,
                "settings": {
                    "primaryDisplay": "FIAT",
                    "displayUnit": "CLASSIC",
                    "selectedCurrency": "EUR",
                    "hideBalance": true,
                    "isPinEnabled": false,
                    "quickPayIntroSeen": true,
                    "isQuickPayEnabled": true,
                    "quickPayAmount": 10,
                    "quickPayDailyLimitMultiplier": 3,
                    "addressTypesToMonitor": ["NATIVE_SEGWIT"],
                    "someFutureSetting": {"nested": [1, 2, 3]}
                }
            }
        """.trimIndent()
    }

    @Test
    fun `the phone's backup gives the units, the currency and quickpay, and ignores every other key`() {
        val settings = parse(ANDROID_BACKUP)

        assertEquals(
            PhoneSettings(
                primaryDisplay = PrimaryDisplay.FIAT,
                displayUnit = BitcoinDisplayUnit.CLASSIC,
                selectedCurrency = "EUR",
                quickPay = QuickPayLimits(isEnabled = true, amountUsd = 10, dailyMultiplier = 3),
            ),
            settings,
        )
        assertEquals(1_000L, settings.quickPay?.thresholdCents)
        assertEquals(3_000L, settings.quickPay?.dailyCapCents)
    }

    @Test
    fun `enums written by ios are read too`() {
        val settings = parse(
            """{"version":1,"createdAt":1,"settings":{"primaryDisplay":{"fiat":{}},"displayUnit":"classic"}}""",
        )

        assertEquals(PrimaryDisplay.FIAT, settings.primaryDisplay)
        assertEquals(BitcoinDisplayUnit.CLASSIC, settings.displayUnit)
    }

    @Test
    fun `a display value that is missing or unreadable is left to the watch`() {
        val settings = parse(
            """{"version":1,"settings":{"primaryDisplay":"GOLD","displayUnit":7,"selectedCurrency":"dollars"}}""",
        )

        assertNull(settings.primaryDisplay)
        assertNull(settings.displayUnit)
        assertNull(settings.selectedCurrency)
        assertEquals(PhoneSettings(null, null, null, null), parse("""{"settings":{}}"""))
    }

    @Test
    fun `quickpay that is off keeps its limits`() {
        val settings = parse(quickPay("false", "5", "5"))

        assertEquals(QuickPayLimits(isEnabled = false, amountUsd = 5, dailyMultiplier = 5), settings.quickPay)
    }

    @Test
    fun `quickpay with a missing, mistyped or out of range value is not adopted`() {
        val settings = """{"isQuickPayEnabled":true,"quickPayAmount":5}"""
        assertNull(parse("""{"version":1,"settings":$settings}""").quickPay)
        assertNull(parse("""{"version":1,"settings":{"quickPayAmount":5,"quickPayDailyLimitMultiplier":5}}""").quickPay)
        for ((isEnabled, amount, multiplier) in listOf(
            Triple("\"true\"", "5", "5"),
            Triple("1", "5", "5"),
            Triple("null", "5", "5"),
            Triple("true", "\"5\"", "5"),
            Triple("true", "5.5", "5"),
            Triple("true", "0", "5"),
            Triple("true", "-5", "5"),
            Triple("true", "51", "5"),
            Triple("true", "99999999999", "5"),
            Triple("true", "5", "0"),
            Triple("true", "5", "51"),
            Triple("true", "5", "[5]"),
        )) {
            assertNull(parse(quickPay(isEnabled, amount, multiplier)).quickPay, "$isEnabled $amount $multiplier")
        }
    }

    @Test
    fun `the largest values the phone offers are adopted`() {
        val settings = parse(quickPay("true", "50", "50"))

        assertEquals(QuickPayLimits(isEnabled = true, amountUsd = 50, dailyMultiplier = 50), settings.quickPay)
        assertEquals(250_000L, settings.quickPay?.dailyCapCents)
    }

    @Test
    fun `a newer layout is refused`() {
        val error = assertFailsWith<PhoneSettingsError.NewerSchema> {
            parse("""{"version":2,"createdAt":1,"settings":{"isQuickPayEnabled":true}}""")
        }

        assertEquals("The settings backup has version '2'", error.message)
    }

    @Test
    fun `what is not the phone's envelope is refused`() {
        for (raw in listOf(
            "",
            "not json",
            "[]",
            "\"SETTINGS\"",
            "{}",
            """{"version":1}""",
            """{"version":1,"settings":[]}""",
            """{"version":1,"settings":"{}"}""",
            """{"version":"new","settings":{}}""",
            """{"version":0,"settings":{}}""",
            """{"version":{},"settings":{}}""",
        )) {
            assertFailsWith<PhoneSettingsError.Unreadable>(raw) { parse(raw) }
        }
        assertFailsWith<PhoneSettingsError.Unreadable> {
            PhoneSettingsParser.parse(ByteArray(PhoneSettingsParser.MAX_BYTES + 1) { ' '.code.toByte() })
        }
        assertFailsWith<PhoneSettingsError.Unreadable> { PhoneSettingsParser.parse(byteArrayOf(-1, 0, -2)) }
    }

    private fun quickPay(isEnabled: String, amount: String, multiplier: String) =
        """{"version":1,"settings":{"isQuickPayEnabled":$isEnabled,"quickPayAmount":$amount,""" +
            """"quickPayDailyLimitMultiplier":$multiplier}}"""

    private fun parse(raw: String) = PhoneSettingsParser.parse(raw.toByteArray())
}
