package to.bitkit.data

import to.bitkit.models.WidgetType
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetsDataTest {

    @Test
    fun `defaults to the configured widget order`() {
        assertEquals(
            listOf(
                WidgetType.NEWS,
                WidgetType.FACTS,
                WidgetType.PRICE,
                WidgetType.BLOCK,
                WidgetType.WEATHER,
                WidgetType.SUGGESTIONS,
                WidgetType.CALCULATOR,
            ),
            WidgetsData().widgets.map { it.type },
        )
    }
}
