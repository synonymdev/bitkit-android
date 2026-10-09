package to.bitkit.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.sheets.NewTransactionSheetView
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale

@HiltAndroidTest
@ComposeUi
class UsdtReceivePrivacyTest {
    private val originalLocale = Locale.getDefault()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun incomingPaymentHonorsHiddenBalance() {
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
        composeTestRule.onNodeWithText("123.46").assertDoesNotExist()
        composeTestRule.runOnIdle { hidden.value = false }
        composeTestRule.onNodeWithText("123.46").assertIsDisplayed()
    }
}
