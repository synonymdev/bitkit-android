package to.bitkit.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.repositories.MethodId
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class PaykitAcceptedMethodsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun largeLocalizedMethodsWrapAndRemainReachable() {
        val clicked = mutableListOf<PaykitPaymentOption>()
        val methods = persistentListOf(
            MethodId.P2wpkh.rawValue,
            MethodId.Bolt11.rawValue,
            MethodId.UsdtArbitrum.rawValue,
        )
        composeTestRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.GERMANY) }
            val localizedContext = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f),
            ) {
                AppThemeSurface {
                    Box(modifier = Modifier.width(280.dp).testTag("MethodsBounds")) {
                        PaykitAcceptedMethods(
                            title = "USDT",
                            availableMethods = methods,
                            selectedMethods = methods,
                            testTagPrefix = "PaymentRequest",
                            onClickMethod = { clicked += it },
                        )
                    }
                }
            }
        }

        val bounds = composeTestRule.onNodeWithTag("MethodsBounds").fetchSemanticsNode().boundsInRoot
        val chips = PaykitPaymentOption.entries.map { option ->
            composeTestRule.onNodeWithTag("PaymentRequestAccept-${option.id}").also {
                it.assertIsDisplayed().performClick()
            }.fetchSemanticsNode().boundsInRoot
        }
        chips.forEach { chip ->
            assertTrue(chip.left >= bounds.left && chip.right <= bounds.right)
        }
        assertTrue(chips.last().top > chips.first().top)
        assertEquals(PaykitPaymentOption.entries.toList(), clicked)
    }
}
