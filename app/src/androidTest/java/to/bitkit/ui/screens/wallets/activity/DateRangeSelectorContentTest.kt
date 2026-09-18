package to.bitkit.ui.screens.wallets.activity

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.ext.minusMonths
import to.bitkit.ext.plusMonths
import to.bitkit.ext.toMonthYearString
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class DateRangeSelectorContentTest {
    companion object {
        private const val SETTLE_MILLIS = 1_000L
        private const val NEXT_TAPS = 5
        private const val PREV_TAPS = 2

        /** Lowest channel value counted as a drawn day number, which is white on a dark sheet. */
        private const val DAY_NUMBER_CHANNEL_MIN = 0.8f

        /** Drag past touch slop that stays well under the sheet's 100 dp swipe threshold. */
        private const val SUB_THRESHOLD_DRAG_DP = 40

        /** A few frames into the 200 ms month transition, while the slide is still running. */
        private const val MID_TRANSITION_MILLIS = 64L

        /** Grid displacement from its resting position that counts as a slide still in flight. */
        private const val IN_FLIGHT_MIN_PX = 1f
        private val INITIAL_DATE = LocalDate(2025, 1, 15)
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        composeTestRule.mainClock.autoAdvance = false
    }

    @Test
    fun rapidNextMonthTapsAdvanceEveryTapAndKeepGridInPlace() {
        val restingLeft = setContentAndGetGridLeft()

        repeat(NEXT_TAPS) { tapWithoutSettling("NextMonth") }
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MILLIS)

        assertGridSettledOn(INITIAL_DATE.plusMonths(NEXT_TAPS), restingLeft)
    }

    @Test
    fun interleavedRapidTapsAdvanceEveryTapAndKeepGridInPlace() {
        val restingLeft = setContentAndGetGridLeft()

        repeat(NEXT_TAPS) { tapWithoutSettling("NextMonth") }
        repeat(PREV_TAPS) { tapWithoutSettling("PrevMonth") }
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MILLIS)

        assertGridSettledOn(INITIAL_DATE.plusMonths(NEXT_TAPS).minusMonths(PREV_TAPS), restingLeft)
    }

    @Test
    fun swipeDuringMonthTransitionDoesNotAdvanceASecondMonth() {
        val restingLeft = setContentAndGetGridLeft()

        tapWithoutSettling("NextMonth")
        composeTestRule.onNodeWithTag("CalendarSwipeArea").performTouchInput {
            down(centerRight)
            moveTo(centerLeft)
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MILLIS)

        assertGridSettledOn(INITIAL_DATE.plusMonths(1), restingLeft)
    }

    @Test
    fun shortSwipeDuringMonthTransitionKeepsTheSlideRunning() {
        val restingLeft = setContentAndGetGridLeft()

        tapWithoutSettling("NextMonth")
        dragBelowThreshold(endGestureWith = { up() })

        assertSlideStillInFlight(restingLeft)
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertGridSettledOn(INITIAL_DATE.plusMonths(1), restingLeft)
    }

    @Test
    fun cancelledSwipeDuringMonthTransitionKeepsTheSlideRunning() {
        val restingLeft = setContentAndGetGridLeft()

        tapWithoutSettling("NextMonth")
        dragBelowThreshold(endGestureWith = { cancel() })

        assertSlideStillInFlight(restingLeft)
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertGridSettledOn(INITIAL_DATE.plusMonths(1), restingLeft)
    }

    private fun dragBelowThreshold(endGestureWith: TouchInjectionScope.() -> Unit) {
        composeTestRule.onNodeWithTag("CalendarSwipeArea").performTouchInput {
            down(center)
            moveTo(center - Offset(viewConfiguration.touchSlop + SUB_THRESHOLD_DRAG_DP.dp.toPx(), 0f))
            endGestureWith()
        }
    }

    private fun assertSlideStillInFlight(restingLeft: Float) {
        composeTestRule.mainClock.advanceTimeBy(MID_TRANSITION_MILLIS)
        val currentLeft = composeTestRule.onNodeWithTag("CalendarGrid").getUnclippedBoundsInRoot().left.value
        assertTrue(
            abs(currentLeft - restingLeft) > IN_FLIGHT_MIN_PX,
            "month slide was cut short, grid already rests at $restingLeft",
        )
    }

    private fun setContentAndGetGridLeft(): Float {
        val initialStartDate = INITIAL_DATE
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
        composeTestRule.setContent {
            AppThemeSurface {
                DateRangeSelectorContent(initialStartDate = initialStartDate)
            }
        }
        composeTestRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        return composeTestRule.onNodeWithTag("CalendarGrid").getUnclippedBoundsInRoot().left.value
    }

    private fun tapWithoutSettling(tag: String) {
        composeTestRule.onNodeWithTag(tag).performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    private fun assertGridSettledOn(expectedMonth: LocalDate, restingLeft: Float) {
        composeTestRule.onNodeWithText(expectedMonth.toMonthYearString()).assertIsDisplayed()
        composeTestRule.onNodeWithTag("Day-1").assertIsDisplayed()
        assertEquals(
            restingLeft,
            composeTestRule.onNodeWithTag("CalendarGrid").getUnclippedBoundsInRoot().left.value,
        )
        assertGridIsOpaque()
    }

    private fun assertGridIsOpaque() {
        val pixels = composeTestRule.onNodeWithTag("CalendarGrid").captureToImage().toPixelMap()
        val drawsDayNumbers = (0 until pixels.height).any { y ->
            (0 until pixels.width).any { x ->
                val pixel = pixels[x, y]
                pixel.red > DAY_NUMBER_CHANNEL_MIN &&
                    pixel.green > DAY_NUMBER_CHANNEL_MIN &&
                    pixel.blue > DAY_NUMBER_CHANNEL_MIN
            }
        }
        assertTrue(drawsDayNumbers, "calendar grid is transparent, no day number pixels were drawn")
    }
}
