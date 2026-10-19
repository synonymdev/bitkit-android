package to.bitkit.utils.timedsheets

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TimedSheetManagerTest : BaseUnitTest() {
    private val testScope = TestScope()
    private lateinit var sut: TimedSheetManager
    private var canShowSheet = true
    private val shownSheets = mutableListOf<TimedSheetType>()

    @Before
    fun setUp() {
        sut = TimedSheetManager(testScope)
    }

    private fun enterHome() = sut.onHomeScreenEntered(
        canShowSheet = { canShowSheet },
        showSheet = {
            shownSheets.add(it)
            sut.onSheetShown()
        },
    )

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

        enterHome()
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
        enterHome()

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
        enterHome()
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
        enterHome()
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
        enterHome()
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
        enterHome()
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

        enterHome()
        testScope.advanceTimeBy(2100)

        assertEquals(TimedSheetType.APP_UPDATE, sut.currentSheet.value)
    }

    @Test
    fun `opening a sheet cancels the timer without consuming the reminder`() = test {
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.USDT
            on { priority } doReturn TimedSheetType.USDT.priority
        }
        whenever(sheet.shouldShow()).thenReturn(true)
        sut.registerSheet(sheet)

        enterHome()
        testScope.advanceTimeBy(1000)
        sut.onSheetShown()
        testScope.advanceTimeBy(3000)

        assertNull(sut.currentSheet.value)
        assertEquals(emptyList(), shownSheets)
        verify(sheet, never()).shouldShow()
        verify(sheet, never()).onShown()
        verify(sheet, never()).onDismissed()

        enterHome()
        testScope.advanceTimeBy(2100)
        assertEquals(listOf(TimedSheetType.USDT), shownSheets)
    }

    @Test
    fun `resuming Home with another sheet open keeps every reminder queued`() = test {
        for (type in TimedSheetType.entries) {
            val sheet = mock<TimedSheetItem> {
                on { this.type } doReturn type
                on { priority } doReturn type.priority
            }
            whenever(sheet.shouldShow()).thenReturn(true)
            sut.registerSheet(sheet)
            canShowSheet = false

            enterHome()
            testScope.advanceTimeBy(2100)
            assertNull(sut.currentSheet.value)
            verify(sheet, never()).shouldShow()
            verify(sheet, never()).onShown()

            canShowSheet = true
            testScope.advanceTimeBy(3000)
            assertNull(sut.currentSheet.value)

            enterHome()
            testScope.advanceTimeBy(2100)
            assertEquals(type, sut.currentSheet.value)
            assertEquals(type, shownSheets.last())
            sut.dismissCurrentSheet()
            testScope.runCurrent()
        }
        assertEquals(TimedSheetType.entries.toList(), shownSheets)
    }

    @Test
    fun `rechecks presentation after asynchronous eligibility and retries on next Home visit`() = test {
        val finishCheck = CompletableDeferred<Boolean>()
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.USDT
            on { priority } doReturn TimedSheetType.USDT.priority
        }
        whenever(sheet.shouldShow()).doSuspendableAnswer { finishCheck.await() }
        sut.registerSheet(sheet)

        enterHome()
        testScope.advanceTimeBy(2100)
        canShowSheet = false
        finishCheck.complete(true)
        testScope.runCurrent()

        assertNull(sut.currentSheet.value)
        assertEquals(emptyList(), shownSheets)
        verify(sheet, never()).onShown()

        canShowSheet = true
        enterHome()
        testScope.advanceTimeBy(2100)
        assertEquals(listOf(TimedSheetType.USDT), shownSheets)
    }

    @Test
    fun `cancelled eligibility cannot show a reminder even if it ignores cancellation`() = test {
        val finishCheck = CompletableDeferred<Boolean>()
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.APP_UPDATE
            on { priority } doReturn TimedSheetType.APP_UPDATE.priority
        }
        whenever(sheet.shouldShow()).doSuspendableAnswer {
            withContext(NonCancellable) { finishCheck.await() }
        }
        sut.registerSheet(sheet)

        enterHome()
        testScope.advanceTimeBy(2100)
        sut.onSheetShown()
        finishCheck.complete(true)
        testScope.runCurrent()

        assertNull(sut.currentSheet.value)
        assertEquals(emptyList(), shownSheets)
        verify(sheet, never()).onShown()

        enterHome()
        testScope.advanceTimeBy(2100)
        assertEquals(listOf(TimedSheetType.APP_UPDATE), shownSheets)
    }

    @Test
    fun `presentation does not cancel its own onShown callback`() = test {
        val finishedShowing = CompletableDeferred<Unit>()
        val sheet = mock<TimedSheetItem> {
            on { type } doReturn TimedSheetType.USDT
            on { priority } doReturn TimedSheetType.USDT.priority
        }
        whenever(sheet.shouldShow()).thenReturn(true)
        whenever(sheet.onShown()).doSuspendableAnswer {
            delay(1.seconds)
            finishedShowing.complete(Unit)
            Unit
        }
        sut.registerSheet(sheet)

        enterHome()
        testScope.advanceTimeBy(3100)

        assertEquals(listOf(TimedSheetType.USDT), shownSheets)
        assertEquals(true, finishedShowing.isCompleted)
    }
}
