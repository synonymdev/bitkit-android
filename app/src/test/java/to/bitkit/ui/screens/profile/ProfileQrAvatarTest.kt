package to.bitkit.ui.screens.profile

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileQrAvatarTest {
    @Test
    fun `smallest fitted qr stays readable under the avatar`() {
        val qrWidth = 200.dp

        val readable = publicKeys().filter { decodes(it, qrWidth, avatarSize = 0.dp) }
        val hiddenByAvatar = readable.filterNot { decodes(it, qrWidth, fittedAvatarSize(qrWidth)) }

        assertTrue(readable.size > KEY_COUNT / 2)
        assertEquals(emptyList(), hiddenByAvatar)
    }

    @Test
    fun `avatar keeps its full size over a large qr`() {
        assertEquals(68.dp, fittedAvatarSize(400.dp))
    }

    private fun publicKeys(): List<String> {
        val random = Random(SEED)
        return List(KEY_COUNT) {
            "pubky" + (1..KEY_LENGTH).map { Z_BASE32[random.nextInt(Z_BASE32.length)] }.joinToString("")
        }
    }

    /** Draws the QR at an 8-inch tablet's density, covers its centre with the avatar circle and decodes it */
    private fun decodes(content: String, qrWidth: Dp, avatarSize: Dp): Boolean {
        val px = (qrWidth.value * TABLET_DENSITY).toInt()
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 2))
        val radius = px * (avatarSize / qrWidth) / 2
        val center = px / 2f
        val pixels = IntArray(px * px) { i ->
            val dx = i % px - center
            val dy = i / px - center
            val covered = dx * dx + dy * dy <= radius * radius
            if (!covered && matrix[i % px, i / px]) BLACK else WHITE
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(px, px, pixels)))
        return runCatching { QRCodeReader().decode(bitmap).text == content }.getOrDefault(false)
    }

    private companion object {
        const val SEED = 7
        const val KEY_COUNT = 20
        const val KEY_LENGTH = 52
        const val Z_BASE32 = "ybndrfg8ejkmcpqxot1uwisza345h769"
        const val TABLET_DENSITY = 213 / 160f
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
