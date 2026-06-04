package to.bitkit.ui.sheets

import to.bitkit.models.WidgetType
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetsRouteTest {

    @Test
    fun `maps widget types to preview routes`() {
        assertEquals(WidgetsRoute.BlocksPreview, WidgetType.BLOCK.toWidgetsPreviewRoute())
        assertEquals(WidgetsRoute.CalculatorPreview, WidgetType.CALCULATOR.toWidgetsPreviewRoute())
        assertEquals(WidgetsRoute.FactsPreview, WidgetType.FACTS.toWidgetsPreviewRoute())
        assertEquals(WidgetsRoute.HeadlinesPreview, WidgetType.NEWS.toWidgetsPreviewRoute())
        assertEquals(WidgetsRoute.PricePreview, WidgetType.PRICE.toWidgetsPreviewRoute())
        assertEquals(WidgetsRoute.WeatherPreview, WidgetType.WEATHER.toWidgetsPreviewRoute())
        assertEquals(WidgetsRoute.SuggestionsPreview, WidgetType.SUGGESTIONS.toWidgetsPreviewRoute())
    }
}
