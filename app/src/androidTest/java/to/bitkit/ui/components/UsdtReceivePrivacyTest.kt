package to.bitkit.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.setClipboardText
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.screens.wallets.usdt.UsdtReceiveContent
import to.bitkit.ui.sheets.NewTransactionSheetView
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@ComposeUi
class UsdtReceivePrivacyTest {
    private val originalLocale = Locale.getDefault()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        Locale.setDefault(Locale.US)
        hiltRule.inject()
    }

    @After
    fun teardown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun copyAndShareFollowReceiveAddressWhileQrKeepsPaymentUri() {
        val wallet = "0x18503bD685aF84E6A7bC289898bAa1C3f5d8527E"
        val deposit = "0x1234567890123456789012345678901234567890"
        val arbitrumUri = "ethereum:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9@42161/transfer?address=$wallet"
        val polygonUri = "ethereum:0xc2132D05D31c914a87C6611C10748AEb04B58e8F@137/transfer?address=$deposit"
        val cases = listOf(
            ReceiveCase(wallet, "Arbitrum One", arbitrumUri),
            ReceiveCase(wallet, "Arbitrum One", "$arbitrumUri&uint256=1234567", "1.234567"),
            ReceiveCase(deposit, "Polygon", "$polygonUri&uint256=3500000", "3.5"),
            ReceiveCase(wallet, "Arbitrum One", arbitrumUri),
        )
        composeTestRule.mainClock.autoAdvance = true
        val current = mutableStateOf(cases.first())
        lateinit var context: Context
        var shared: Intent? = null
        composeTestRule.setContent {
            val base = LocalContext.current
            context = object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    shared = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                }
            }
            CompositionLocalProvider(LocalContext provides context) {
                AppThemeSurface {
                    Column {
                        val receive = current.value
                        UsdtReceiveContent(receive.address, receive.uri, null, receive.network, {}, {}, receive.amount)
                    }
                }
            }
        }
        for (receive in cases) {
            composeTestRule.runOnIdle { current.value = receive }
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodes(hasTestTag("QRCode") and hasContentDescription(receive.uri), useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1
            }
            composeTestRule.runOnIdle { context.setClipboardText("previous clipboard") }
            composeTestRule.onNodeWithTag("QRCode", useUnmergedTree = true).assertContentDescriptionEquals(receive.uri).performClick()
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitUntil(5000) { context.getClipboardText() == receive.address }
            composeTestRule.runOnIdle { context.setClipboardText("previous clipboard") }
            composeTestRule.onNodeWithTag("ReceiveCopyQR").performClick()
            composeTestRule.waitUntil(5000) { context.getClipboardText() == receive.address }
            for (details in listOf(false, true)) {
                if (details) composeTestRule.onNodeWithTag("UsdtReceiveDetails").performClick()
                val shareLabel = context.getString(R.string.common__share)
                composeTestRule.waitUntil(5000) {
                    composeTestRule.onAllNodes(hasText(shareLabel) and isEnabled()).fetchSemanticsNodes().size == 1
                }
                composeTestRule.onNodeWithText(shareLabel).performClick()
                composeTestRule.runOnIdle {
                    val intent = assertNotNull(shared)
                    val networkTitle = context.getString(R.string.usdt__receive_network, receive.network)
                    val expected = "$networkTitle\n${receive.address}" +
                        if (receive.amount.isEmpty()) "" else "\n${receive.amount} USDT"
                    assertEquals(expected, intent.getStringExtra(Intent.EXTRA_TEXT))
                    val imageUri = assertNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    val bitmap = context.contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                    assertEquals(receive.uri, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
                    shared = null
                }
            }
            composeTestRule.runOnIdle { context.setClipboardText("previous clipboard") }
            composeTestRule.onNodeWithText(context.getString(R.string.common__copy)).performClick()
            composeTestRule.waitUntil(5000) { context.getClipboardText() == receive.address }
            composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
            composeTestRule.onNodeWithTag("ReceiveCopyQR").assertIsDisplayed()
        }
    }

    private data class ReceiveCase(val address: String, val network: String, val uri: String, val amount: String = "")

    @Test
    fun incomingPaymentHonorsHiddenBalance() {
        composeTestRule.mainClock.autoAdvance = false
        val hidden = mutableStateOf(true)
        composeTestRule.setContent {
            AppThemeSurface {
                NewTransactionSheetView(
                    details = NewTransactionSheetDetails(
                        type = NewTransactionSheetType.ONCHAIN,
                        direction = NewTransactionSheetDirection.RECEIVED,
                        usdtAmount = 123_456_789uL,
                    ),
                    onCloseClick = {},
                    onDetailClick = {},
                    hideBalance = hidden.value,
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.onNodeWithText("123.46").assertDoesNotExist()
        composeTestRule.runOnIdle { hidden.value = false }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("123.46").assertIsDisplayed()
    }
}
