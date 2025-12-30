package to.bitkit.utils.timedsheets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType

@OptIn(ExperimentalCoroutinesApi::class)
class TimedSheetManagerTest : BaseUnitTest() {
    private val testScope = TestScope()
    private lateinit var sut: TimedSheetManager

    @Before
    fun setUp() {
        sut = TimedSheetManager(testScope)
    }

    @Test
    fun `shows highest priority eligible sheet first`() = test {
        val highPrioritySheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.APP_UPDATE
            on { priority } doReturn 5
        }
        val lowPrioritySheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.HIGH_BALANCE
            on { priority } doReturn 1
        }

        whenever(highPrioritySheet.shouldShow()).thenReturn(true)
        whenever(lowPrioritySheet.shouldShow()).thenReturn(true)

        sut.registerSheet(lowPrioritySheet)
        sut.registerSheet(highPrioritySheet)

        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)

        assertEquals(TimedSheetType.APP_UPDATE, sut.currentSheet.value)
        verify(highPrioritySheet).onShown()
    }

    @Test
    fun `does not show sheets before 2s delay`() = test {
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.BACKUP
            on { priority } doReturn 4
        }
        whenever(sheet.shouldShow()).thenReturn(true)

        sut.registerSheet(sheet)
        sut.onHomeScreenEntered()

        testScope.advanceTimeBy(1000)
        assertNull(sut.currentSheet.value)

        testScope.advanceTimeBy(1100)
        assertEquals(TimedSheetType.BACKUP, sut.currentSheet.value)
    }

    @Test
    fun `cancels check when leaving home screen`() = test {
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.BACKUP
            on { priority } doReturn 4
        }
        whenever(sheet.shouldShow()).thenReturn(true)

        sut.registerSheet(sheet)
        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(1000)
        sut.onHomeScreenExited()

        testScope.advanceTimeBy(2000)
        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `clears queue when skipQueue is true`() = test {
        val sheet1 = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.APP_UPDATE
            on { priority } doReturn 5
        }
        val sheet2 = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.BACKUP
            on { priority } doReturn 4
        }
        whenever(sheet1.shouldShow()).thenReturn(true)
        whenever(sheet2.shouldShow()).thenReturn(true)

        sut.registerSheet(sheet1)
        sut.registerSheet(sheet2)
        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)

        assertEquals(TimedSheetType.APP_UPDATE, sut.currentSheet.value)

        sut.dismissCurrentSheet()
        testScope.advanceTimeBy(100)

        assertNull(sut.currentSheet.value)
        verify(sheet1).onDismissed()
    }

    @Test
    fun `does not show sheets when none eligible`() = test {
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.BACKUP
            on { priority } doReturn 4
        }
        whenever(sheet.shouldShow()).thenReturn(false)

        sut.registerSheet(sheet)
        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)

        assertNull(sut.currentSheet.value)
    }

    @Test
    fun `removes sheet from queue after showing`() = test {
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.BACKUP
            on { priority } doReturn 4
        }
        whenever(sheet.shouldShow()).thenReturn(true)

        sut.registerSheet(sheet)
        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)

        assertEquals(TimedSheetType.BACKUP, sut.currentSheet.value)

        sut.dismissCurrentSheet()
        testScope.advanceTimeBy(100)

        assertNull(sut.currentSheet.value)
        verify(sheet).onShown()
        verify(sheet).onDismissed()
    }

    @Test
    fun `registers sheets sorted by priority`() = test {
        val sheet1 = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.HIGH_BALANCE
            on { priority } doReturn 1
        }
        val sheet2 = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.BACKUP
            on { priority } doReturn 4
        }
        val sheet3 = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.APP_UPDATE
            on { priority } doReturn 5
        }

        whenever(sheet1.shouldShow()).thenReturn(true)
        whenever(sheet2.shouldShow()).thenReturn(true)
        whenever(sheet3.shouldShow()).thenReturn(true)

        sut.registerSheet(sheet1)
        sut.registerSheet(sheet2)
        sut.registerSheet(sheet3)

        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)

        assertEquals(TimedSheetType.APP_UPDATE, sut.currentSheet.value)
    }
}
