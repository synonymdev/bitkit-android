package to.bitkit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.bitkit.repositories.PaykitReceiptStatus
import to.bitkit.ui.screens.wallets.activity.components.AmountViewContent
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertTrue

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-w412dp-h915dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class PaykitReceiptStatusViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a failed receipt keeps retry and the date in a german activity row`() {
        composeRule.setContent {
            AppThemeSurface {
                // ActivityRow's layout: list and row padding, 40dp icon, weighted text column, amount.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).padding(16.dp),
                ) {
                    Box(Modifier.size(40.dp))
                    Box(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        BodyMSB(text = "Empfangen")
                        ActivitySubtitleWithReceipt(
                            subtitle = "14. Nov., 03:10",
                            receiptStatus = PaykitReceiptStatus.Failed,
                            onRetryClick = {},
                        )
                    }
                    Box(Modifier.size(16.dp))
                    AmountViewContent(title = "15 000", titlePrefix = "+", subtitle = "12,34", subtitleSymbol = "€")
                }
            }
        }

        val retry = composeRule.onNodeWithTag("PaykitReceiptRetry", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val date = composeRule.onNodeWithText("14. Nov., 03:10", useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertTrue(retry.width.value > 0f, "Retry is ${retry.width} wide")
        assertTrue(date.width.value > 0f, "the date is ${date.width} wide")
    }
}
