package to.bitkit.ui.screens.scanner

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrScanningScreenTest {
    @Test
    fun `text payload does not include binary fallback`() {
        val text = "1234567890123456"

        val payload = textQrCodePayload(text)

        assertEquals(text, payload.text)
        assertNull(payload.rawBytes)
    }
}
