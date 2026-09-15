package to.bitkit.ui.screens.widgets.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

/**
 * Regression pin for #647 — the widget list stays scrollable while reordering.
 *
 * The old edit UI attached `detectDragGesturesAfterLongPress` to the whole card, so a vertical
 * swipe anywhere on a widget was swallowed and the page could not be scrolled. Reordering now
 * belongs to the dedicated drag handle only, so a gesture on the card body must never reorder.
 */
@HiltAndroidTest
@ComposeUi
class EditableWidgetGridTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val moves = mutableListOf<Pair<Int, Int>>()

    private val items = listOf(
        WidgetWithPosition(type = WidgetType.PRICE, position = 0),
        WidgetWithPosition(type = WidgetType.NEWS, position = 1),
        WidgetWithPosition(type = WidgetType.BLOCK, position = 2),
        WidgetWithPosition(type = WidgetType.WEATHER, position = 3),
    ).toImmutableList()

    @Before
    fun setup() {
        hiltRule.inject()
        moves.clear()
    }

    private fun setContent() {
        composeTestRule.setContent {
            AppThemeSurface {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("ScrollHost")
                ) {
                    EditableWidgetGrid(
                        items = items,
                        onMove = { from, to -> moves += from to to },
                        onDelete = {},
                        onSettings = {},
                    ) { widget ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .testTag("Card-${widget.type.name}")
                        )
                    }
                }
            }
        }
    }

    @Test
    fun whenSwipingOnCardBody_shouldNotReorderWidgets() {
        setContent()

        composeTestRule.onNodeWithTag("Card-${WidgetType.PRICE.name}").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertEquals(emptyList(), moves)
    }

    @Test
    fun whenSwipingOnScrollHost_shouldNotReorderWidgets() {
        setContent()

        composeTestRule.onNodeWithTag("ScrollHost").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertEquals(emptyList(), moves)
    }

    @Test
    fun whenEditModeActive_shouldExposeADedicatedDragHandlePerWidget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        setContent()

        items.forEach { widget ->
            val name = context.getString(widget.type.title)
            composeTestRule
                .onNodeWithTag("${name}_WidgetActionDrag", useUnmergedTree = true)
                .assertExists()
        }
    }
}
