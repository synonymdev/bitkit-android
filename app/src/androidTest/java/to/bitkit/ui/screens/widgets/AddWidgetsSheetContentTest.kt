package to.bitkit.ui.screens.widgets

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface

@ComposeUi
class AddWidgetsSheetContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testGalleryShowsFigmaOrderedVisibleCards() {
        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("widgets_gallery_screen").assertExists()
        composeTestRule.onNodeWithTag("WidgetListItem-price").assertIsDisplayed()
        composeTestRule.onNodeWithTag("WidgetListItem-weather").assertIsDisplayed()

        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-calculator"))
        composeTestRule.onNodeWithTag("WidgetListItem-calculator").assertIsDisplayed()
    }

    @Test
    fun testGalleryTitleScrollsWithContent() {
        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("widgets_gallery_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-suggestions"))
        composeTestRule.onNodeWithTag("widgets_gallery_title").assertIsNotDisplayed()
    }

    @Test
    fun testGalleryUsesWidgetPreviewComponents() {
        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("price_card_small_chart").assertIsDisplayed()
        composeTestRule.onNodeWithTag("weather_card_small").assertIsDisplayed()
        composeTestRule.onNodeWithTag("headline_card_wide").assertIsDisplayed()
        composeTestRule.onNodeWithTag("block_label").assertIsDisplayed()

        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-suggestions"))
        composeTestRule.onNodeWithTag("Suggestion-back_up").assertIsDisplayed()
    }

    @Test
    fun testGalleryUsesProvidedWidgetData() {
        val article = ArticleModel(
            timeAgo = "5 min ago",
            title = "Live preview headline",
            publisher = "live.source",
            link = "https://live.source",
        )
        val block = BlockModel(
            height = "999,999",
            time = "09:08:07 UTC",
            date = "5/29/2026",
            transactionCount = "9,876",
            size = "1,234kb",
            fees = "50 000",
        )

        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                    article = article,
                    block = block,
                    fact = "Live preview fact",
                )
            }
        }

        composeTestRule.onNodeWithText("Live preview headline").assertIsDisplayed()
        composeTestRule.onNodeWithText("live.source").assertIsDisplayed()

        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-blocks"))
        composeTestRule.onNodeWithText("999,999").assertIsDisplayed()

        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-facts"))
        composeTestRule.onNodeWithText("Live preview fact").assertIsDisplayed()
    }

    @Test
    fun testTwoColumnGalleryItemsHaveEqualHeight() {
        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                )
            }
        }

        val priceBounds = composeTestRule.onNodeWithTag("WidgetListItem-price").getUnclippedBoundsInRoot()
        val weatherBounds = composeTestRule.onNodeWithTag("WidgetListItem-weather").getUnclippedBoundsInRoot()

        assertEquals(priceBounds.bottom - priceBounds.top, weatherBounds.bottom - weatherBounds.top)

        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-calculator"))

        val factsBounds = composeTestRule.onNodeWithTag("WidgetListItem-facts-layout").getUnclippedBoundsInRoot()
        val calculatorBounds = composeTestRule.onNodeWithTag("WidgetListItem-calculator-layout").getUnclippedBoundsInRoot()

        assertEquals(factsBounds.bottom - factsBounds.top, calculatorBounds.bottom - calculatorBounds.top)
    }

    @Test
    fun testGalleryTapSelectsWidget() {
        var selectedWidget: WidgetType? = null

        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = { selectedWidget = it },
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("WidgetListItem-weather").performClick()

        assert(selectedWidget == WidgetType.WEATHER)

        selectedWidget = null
        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-facts"))
        composeTestRule.onNodeWithTag("WidgetListItem-facts").performClick()

        assert(selectedWidget == WidgetType.FACTS)
    }

    @Test
    fun testPriceChartTapSelectsWidget() {
        var selectedWidget: WidgetType? = null

        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = { selectedWidget = it },
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("price_card_small_chart").performTouchInput {
            down(center)
            up()
        }

        assert(selectedWidget == WidgetType.PRICE)
    }

    @Test
    fun testHeadlineContentTapSelectsWidget() {
        var selectedWidget: WidgetType? = null

        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = true,
                    onWidgetSelected = { selectedWidget = it },
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onAllNodesWithTag("headline_text")[0].performTouchInput {
            down(center)
            up()
        }

        assert(selectedWidget == WidgetType.NEWS)
    }

    @Test
    fun testDisabledGalleryShowsSettingsButton() {
        var enableSettingsClicked = false

        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = false,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = { enableSettingsClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("WidgetListItem-price").assertIsDisplayed()
        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-calculator"))
        composeTestRule.onNodeWithTag("WidgetListItem-calculator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("widgets_gallery_scroll")
            .performScrollToNode(hasTestTag("WidgetListItem-suggestions"))
        composeTestRule.onNodeWithTag("WidgetListItem-suggestions").assertIsDisplayed()

        composeTestRule.onNodeWithTag("WidgetEnableInSettings").assertExists().performClick()
        assert(enableSettingsClicked)
    }

    @Test
    fun testDisabledGalleryHeadlineCardIsNotClickable() {
        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = false,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("headline_card_wide").assertHasNoClickAction()
    }

    @Test
    fun testSettingsButtonHasBottomPadding() {
        composeTestRule.setContent {
            AppThemeSurface {
                AddWidgetsSheetContent(
                    fiatSymbol = "$",
                    showWidgets = false,
                    onWidgetSelected = {},
                    onEnableInSettingsClick = {},
                )
            }
        }

        val rootBottom = composeTestRule.onRoot().getUnclippedBoundsInRoot().bottom
        val buttonBottom = composeTestRule.onNodeWithTag("WidgetEnableInSettings")
            .getUnclippedBoundsInRoot()
            .bottom

        assert(buttonBottom < rootBottom)
    }
}
