package to.bitkit.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@HiltAndroidTest
@ComposeUi
class QrCodeImageTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun displayedPaymentCodesDecodeWithLogoAtCompactAndRegularSizes() {
        val address = "0x18503bD685aF84E6A7bC289898bAa1C3f5d8527E"
        val usdt = "ethereum:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9@42161/transfer?address=$address"
        val cases = listOf(
            usdt to R.drawable.tether_circle,
            "$usdt&uint256=3500000" to R.drawable.tether_circle,
            "bitcoin:bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" to R.drawable.ic_btc_circle,
        )
        var renderedContent: String? = null
        val payload = mutableStateOf(cases.first())
        val width = mutableStateOf(120.dp)
        compose.setContent {
            AppThemeSurface {
                QrCodeImage(
                    content = payload.value.first,
                    logoPainter = painterResource(payload.value.second),
                    onBitmapGenerated = { renderedContent = it?.let { payload.value.first } },
                    modifier = Modifier.size(width.value).testTag("DisplayedQr")
                )
            }
        }
        for (size in listOf(120.dp, 160.dp, 240.dp, 320.dp)) {
            for (case in cases) {
                compose.runOnIdle {
                    payload.value = case
                    width.value = size
                }
                compose.waitUntil(5000) { renderedContent == case.first }
                compose.mainClock.advanceTimeBy(500)
                val bitmap = compose.onNodeWithTag("DisplayedQr", useUnmergedTree = true)
                    .captureToImage().asAndroidBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                assertEquals(case.first, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
            }
        }
    }
}
