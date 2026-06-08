package to.bitkit.ui.screens.widgets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import to.bitkit.data.WidgetsData
import to.bitkit.models.WidgetSize
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.test.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSizeDraftTest : BaseUnitTest() {

    private val widgetsData = MutableStateFlow(WidgetsData(widgets = emptyList()))

    @Before
    fun setUp() {
        widgetsData.value = WidgetsData(widgets = emptyList())
    }

    @Test
    fun `unsaved widget defaults to small for wide types`() = test {
        val draft = WidgetSizeDraft(backgroundScope, WidgetType.PRICE, widgetsData)
        val collector = backgroundScope.launch { draft.size.collect {} }

        advanceUntilIdle()

        assertEquals(WidgetSize.SMALL, draft.size.first())
        collector.cancel()
    }

    @Test
    fun `saved widget reflects persisted size`() = test {
        widgetsData.value = WidgetsData(
            widgets = listOf(
                WidgetWithPosition(type = WidgetType.PRICE, position = 0, size = WidgetSize.WIDE),
            ),
        )
        val draft = WidgetSizeDraft(backgroundScope, WidgetType.PRICE, widgetsData)
        val collector = backgroundScope.launch { draft.size.collect {} }

        advanceUntilIdle()

        assertEquals(WidgetSize.WIDE, draft.size.first())
        collector.cancel()
    }

    @Test
    fun `user draft overrides saved size`() = test {
        widgetsData.value = WidgetsData(
            widgets = listOf(
                WidgetWithPosition(type = WidgetType.FACTS, position = 0, size = WidgetSize.SMALL),
            ),
        )
        val draft = WidgetSizeDraft(backgroundScope, WidgetType.FACTS, widgetsData)
        val collector = backgroundScope.launch { draft.size.collect {} }

        advanceUntilIdle()
        draft.set(WidgetSize.WIDE)

        advanceUntilIdle()

        assertEquals(WidgetSize.WIDE, draft.size.first())
        collector.cancel()
    }
}
