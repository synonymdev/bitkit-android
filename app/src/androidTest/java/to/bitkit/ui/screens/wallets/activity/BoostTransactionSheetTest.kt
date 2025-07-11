package to.bitkit.ui.screens.wallets.activity

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.ui.theme.AppThemeSurface

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BoostTransactionContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private val defaultUiState = BoostTransactionUiState(
        totalFeeSats = 4250UL,
        estimateTime = "±10-20 minutes",
        loading = false,
        isDefaultMode = true,
        feeRate = 4UL,
        boosting = false,
        decreaseEnabled = true,
        increaseEnabled = true
    )

    @Test
    fun boostTransactionContent_customMode_displaysCorrectElements() {
        val customModeUiState = defaultUiState.copy(isDefaultMode = false)

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = customModeUiState
                )
            }
        }

        // Assert custom mode content is visible
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.CUSTOM_MODE_CONTENT)
            .assertIsDisplayed()

        // Assert quantity buttons
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.DECREASE_FEE_BUTTON)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.INCREASE_FEE_BUTTON)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()

        // Assert fee rate display
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.FEE_RATE_TEXT)
            .assertIsDisplayed()

        // Assert estimate time and secondary fee
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.ESTIMATE_TIME)
            .assertIsDisplayed()
            .assertTextContains("±10-20 minutes")

        // Assert use suggested fee button
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.USE_SUGGESTED_FEE_BUTTON)
            .assertIsDisplayed()
            .assertHasClickAction()

        // Assert swipe to confirm
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.SWIPE_TO_CONFIRM)
            .assertIsDisplayed()

        // Assert default mode content is not visible
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.EDIT_FEE_ROW)
            .assertDoesNotExist()
    }

    @Test
    fun boostTransactionContent_loadingState_showsLoadingIndicator() {
        val loadingUiState = defaultUiState.copy(loading = true)

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = loadingUiState
                )
            }
        }

        // Assert loading indicator is shown
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.LOADING_INDICATOR)
            .assertIsDisplayed()

        // Assert other content is not shown
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.EDIT_FEE_ROW)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.CUSTOM_MODE_CONTENT)
            .assertDoesNotExist()
    }

    @Test
    fun defaultModeContent_editRowClick_triggersCallback() {
        var onClickEditCalled = false

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = { onClickEditCalled = true },
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = defaultUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.EDIT_FEE_ROW)
            .performClick()

        assert(onClickEditCalled) { "onClickEdit should have been called" }
    }

    @Test
    fun customModeContent_increaseButtonClick_triggersCallback() {
        val customModeUiState = defaultUiState.copy(isDefaultMode = false)
        var onChangeAmountCalled = false
        var isIncrease: Boolean? = null

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = { increase ->
                        onChangeAmountCalled = true
                        isIncrease = increase
                    },
                    onSwipe = {},
                    uiState = customModeUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.INCREASE_FEE_BUTTON)
            .performClick()

        assert(onChangeAmountCalled) { "onChangeAmount should have been called" }
        assert(isIncrease == true) { "isIncrease should be true" }
    }

    @Test
    fun customModeContent_decreaseButtonClick_triggersCallback() {
        val customModeUiState = defaultUiState.copy(isDefaultMode = false)
        var onChangeAmountCalled = false
        var isIncrease: Boolean? = null

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = { increase ->
                        onChangeAmountCalled = true
                        isIncrease = increase
                    },
                    onSwipe = {},
                    uiState = customModeUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.DECREASE_FEE_BUTTON)
            .performClick()

        assert(onChangeAmountCalled) { "onChangeAmount should have been called" }
        assert(isIncrease == false) { "isIncrease should be false" }
    }

    @Test
    fun customModeContent_useSuggestedFeeButtonClick_triggersCallback() {
        val customModeUiState = defaultUiState.copy(isDefaultMode = false)
        var onClickUseSuggestedFeeCalled = false

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = { onClickUseSuggestedFeeCalled = true },
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = customModeUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.USE_SUGGESTED_FEE_BUTTON)
            .performClick()

        assert(onClickUseSuggestedFeeCalled) { "onClickUseSuggestedFee should have been called" }
    }

    @Test
    fun customModeContent_disabledButtons_areNotEnabled() {
        val disabledUiState = defaultUiState.copy(
            isDefaultMode = false,
            decreaseEnabled = false,
            increaseEnabled = false
        )

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = disabledUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.DECREASE_FEE_BUTTON)
            .assertIsNotEnabled()

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.INCREASE_FEE_BUTTON)
            .assertIsNotEnabled()
    }

    @Test
    fun customModeContent_enabledButtons_areEnabled() {
        val enabledUiState = defaultUiState.copy(
            isDefaultMode = false,
            decreaseEnabled = true,
            increaseEnabled = true
        )

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = enabledUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.DECREASE_FEE_BUTTON)
            .assertIsEnabled()

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.INCREASE_FEE_BUTTON)
            .assertIsEnabled()
    }

    @Test
    fun boostTransactionContent_displaysCorrectEstimateTime() {
        val customEstimateTime = "±5-10 minutes"
        val customUiState = defaultUiState.copy(estimateTime = customEstimateTime)

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = customUiState
                )
            }
        }

        composeTestRule.onNodeWithTag(BoostTransactionTestTags.ESTIMATE_TIME, useUnmergedTree = true)
            .assertTextContains(customEstimateTime)
    }

    @Test
    fun quantityButton_enabled_triggersOnClick() {
        var onClickCalled = false

        composeTestRule.setContent {
            AppThemeSurface {
                QuantityButton(
                    icon = androidx.compose.ui.res.painterResource(android.R.drawable.ic_delete),
                    iconColor = androidx.compose.ui.graphics.Color.Red,
                    backgroundColor = androidx.compose.ui.graphics.Color.Gray,
                    enabled = true,
                    contentDescription = "Test button",
                    onClick = { onClickCalled = true },
                    modifier = androidx.compose.ui.Modifier.testTag("test_quantity_button")
                )
            }
        }

        composeTestRule.onNodeWithTag("test_quantity_button")
            .performClick()

        assert(onClickCalled) { "onClick should have been called" }
    }

    @Test
    fun quantityButton_disabled_doesNotTriggerOnClick() {
        var onClickCalled = false

        composeTestRule.setContent {
            AppThemeSurface {
                QuantityButton(
                    icon = androidx.compose.ui.res.painterResource(android.R.drawable.ic_delete),
                    iconColor = androidx.compose.ui.graphics.Color.Red,
                    backgroundColor = androidx.compose.ui.graphics.Color.Gray,
                    enabled = false,
                    contentDescription = "Test button",
                    onClick = { onClickCalled = true },
                    modifier = androidx.compose.ui.Modifier.testTag("test_quantity_button_disabled")
                )
            }
        }

        composeTestRule.onNodeWithTag("test_quantity_button_disabled")
            .performClick()

        assert(!onClickCalled) { "onClick should not have been called when button is disabled" }
    }

    @Test
    fun loadingState_onlyShowsLoadingIndicator() {
        val loadingUiState = defaultUiState.copy(loading = true)

        composeTestRule.setContent {
            AppThemeSurface {
                BoostTransactionContent(
                    onClickEdit = {},
                    onClickUseSuggestedFee = {},
                    onChangeAmount = {},
                    onSwipe = {},
                    uiState = loadingUiState
                )
            }
        }

        // Only loading indicator should be visible
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.LOADING_INDICATOR)
            .assertIsDisplayed()

        // All other content should be hidden
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.EDIT_FEE_ROW)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.CUSTOM_MODE_CONTENT)
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag(BoostTransactionTestTags.SWIPE_TO_CONFIRM)
            .assertDoesNotExist()
    }
}
