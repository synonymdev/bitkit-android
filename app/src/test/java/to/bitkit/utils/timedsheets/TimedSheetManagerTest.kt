package to.bitkit.utils.timedsheets

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TimedSheetManagerTest : BaseUnitTest() {
    private val testScope = TestScope()
    private lateinit var sut: TimedSheetManager

    @Before
    fun setUp() {
        sut = TimedSheetManager(testScope) { true }
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

    @Test
    fun `retries rejected presentation without consuming the sheet`() = test {
        var canPresent = false
        sut = TimedSheetManager(testScope) { canPresent }
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.USDT
        }
        whenever(sheet.shouldShow()).thenReturn(true)
        sut.registerSheet(sheet)
        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)
        assertNull(sut.currentSheet.value)
        verify(sheet, never()).onShown()

        canPresent = true
        sut.reevaluate()
        testScope.advanceTimeBy(2100)
        assertEquals(TimedSheetType.USDT, sut.currentSheet.value)
        verify(sheet).onShown()

        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)
        assertEquals(TimedSheetType.USDT, sut.currentSheet.value)
        verify(sheet).onShown()
    }

    @Test
    fun `cancelled eligibility check cannot show after leaving home`() = test {
        val finish = CompletableDeferred<Boolean>()
        val sheet = object : TimedSheetItem {
            override val type = TimedSheetType.USDT
            override val priority = type.priority
            override suspend fun shouldShow() = withContext(NonCancellable) { finish.await() }
            override suspend fun onShown() = Unit
            override suspend fun onDismissed() = Unit
        }
        sut.registerSheet(sheet)
        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)
        sut.onHomeScreenExited()
        finish.complete(true)
        testScope.advanceTimeBy(100)
        assertNull(sut.currentSheet.value)

        sut.onHomeScreenEntered()
        testScope.advanceTimeBy(2100)
        assertEquals(TimedSheetType.USDT, sut.currentSheet.value)
    }
}
