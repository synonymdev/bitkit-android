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
}
