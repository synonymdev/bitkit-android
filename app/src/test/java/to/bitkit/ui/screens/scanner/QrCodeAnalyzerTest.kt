package to.bitkit.ui.screens.scanner

import org.junit.Test
import to.bitkit.models.QrCodePayload
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrCodeAnalyzerTest {
    @Test
    fun `text scanner skips preceding binary payload`() {
        val selected = selectQrCodePayload(
            payloads = listOf(
                QrCodePayload(text = null, rawBytes = byteArrayOf(1)),
                QrCodePayload(text = "lightning:invoice", rawBytes = byteArrayOf(2)),
            ),
            acceptsBinaryPayload = false,
        )

        assertEquals("lightning:invoice", selected?.text)
    }

    @Test
    fun `binary scanner prefers text payload`() {
        val selected = selectQrCodePayload(
            payloads = listOf(
                QrCodePayload(text = null, rawBytes = byteArrayOf(1)),
                QrCodePayload(text = "standard seedqr", rawBytes = byteArrayOf(2)),
            ),
            acceptsBinaryPayload = true,
        )

        assertEquals("standard seedqr", selected?.text)
    }

    @Test
    fun `text scanner rejects binary-only payload`() {
        val selected = selectQrCodePayload(
            payloads = listOf(QrCodePayload(text = null, rawBytes = byteArrayOf(1))),
            acceptsBinaryPayload = false,
        )

        assertNull(selected)
    }

    @Test
    fun `binary scanner accepts binary-only payload`() {
        val selected = selectQrCodePayload(
            payloads = listOf(QrCodePayload(text = null, rawBytes = byteArrayOf(1, 2))),
            acceptsBinaryPayload = true,
        )

        assertContentEquals(byteArrayOf(1, 2), selected?.rawBytes)
    }
}
