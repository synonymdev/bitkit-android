package to.bitkit.ui.screens.wallets.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.assertEquals

@ComposeUi
class DateRangeSelectorContentTest {
    companion object {
        private const val SETTLE_MILLIS = 1_000L
        private const val NEXT_TAPS = 5
        private const val PREV_TAPS = 2
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
    }
}
