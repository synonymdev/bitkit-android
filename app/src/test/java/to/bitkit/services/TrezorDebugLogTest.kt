package to.bitkit.services

import org.junit.After
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TrezorDebugLogTest {
    @After
    fun tearDown() {
        TrezorDebugLog.clear()
    }

    @Test
    fun `sensitive diagnostic values are redacted`() {
        TrezorDebugLog.log("REDACTION_TEST", "pin=1234 passphrase='hidden wallet'")

        val line = TrezorDebugLog.lines.value.last { "[REDACTION_TEST]" in it }

        assertContains(line, "pin=<redacted>")
        assertContains(line, "passphrase=<redacted>")
        assertFalse("1234" in line)
        assertFalse("hidden wallet" in line)
    }

    @Test
    fun `structured sensitive diagnostic values are redacted`() {
        TrezorDebugLog.log(
            "STRUCTURED_REDACTION_TEST",
            """{"xpub":"xpub-secret","psbt":"psbt-secret","raw_tx":"raw-secret","pin":"1234","passphrase":"hidden"}""",
        )

        val line = TrezorDebugLog.lines.value.last { "[STRUCTURED_REDACTION_TEST]" in it }

        assertFalse("xpub-secret" in line)
        assertFalse("psbt-secret" in line)
        assertFalse("raw-secret" in line)
        assertFalse("1234" in line)
        assertFalse("hidden" in line)
    }

    @Test
    fun `unquoted multi word sensitive diagnostic values are fully redacted`() {
        TrezorDebugLog.log(
            "MULTI_WORD_REDACTION_TEST",
            "mnemonic=abandon ability able passphrase=my hidden wallet",
        )

        val line = TrezorDebugLog.lines.value.last { "[MULTI_WORD_REDACTION_TEST]" in it }

        assertContains(line, "mnemonic=<redacted>")
        assertFalse("abandon" in line)
        assertFalse("ability" in line)
        assertFalse("able" in line)
        assertFalse("my hidden wallet" in line)
    }
}
