package to.bitkit.ui.screens.widgets.components

import androidx.compose.foundation.ScrollState
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
import kotlin.test.assertTrue

/**
 * Regression pin for #647 — the widget list stays scrollable while reordering.
 *
 * The old edit UI attached `detectDragGesturesAfterLongPress` to the whole card, so a vertical
 * swipe anywhere on a widget was swallowed and the page could not be scrolled. Reordering now
 * belongs to the dedicated drag handle only, so a gesture on the card body must both leave the
 * order alone AND reach the scroll container.
 */
@HiltAndroidTest
@ComposeUi
class EditableWidgetGridTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val moves = mutableListOf<Pair<Int, Int>>()
    private lateinit var scrollState: ScrollState

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
            scrollState = rememberScrollState()
            AppThemeSurface {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Host is deliberately far shorter than the content below, so the grid
                        // always overflows and there is real scrolling to observe.
                        .height(300.dp)
                        .verticalScroll(scrollState)
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
                                .height(400.dp)
                                .testTag("Card-${widget.type.name}")
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        // Guard the premise: without overflow the swipe assertions below prove nothing.
        assertTrue(scrollState.maxValue > 0, "grid must overflow the host for this test to mean anything")
    }

    @Test
    fun whenSwipingOnCardBody_shouldScrollAndNotReorderWidgets() {
        setContent()
        val before = scrollState.value

        composeTestRule.onNodeWithTag("Card-${WidgetType.PRICE.name}").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertTrue(
            scrollState.value > before,
            "swiping a card body must scroll the page (was $before, now ${scrollState.value})",
        )
        assertEquals(emptyList(), moves, "swiping a card body must not reorder widgets")
    }

    @Test
    fun whenSwipingOnScrollHost_shouldScrollAndNotReorderWidgets() {
        setContent()
        val before = scrollState.value

        composeTestRule.onNodeWithTag("ScrollHost").performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        assertTrue(
            scrollState.value > before,
            "swiping the host must scroll the page (was $before, now ${scrollState.value})",
        )
        assertEquals(emptyList(), moves, "swiping the host must not reorder widgets")
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
