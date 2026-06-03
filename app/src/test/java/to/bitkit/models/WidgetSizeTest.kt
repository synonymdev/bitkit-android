package to.bitkit.models

import to.bitkit.data.WidgetsData
import to.bitkit.di.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetSizeTest {

    @Test
    fun `default maps wide types`() {
        assertEquals(WidgetSize.WIDE, WidgetSize.default(WidgetType.PRICE))
        assertEquals(WidgetSize.WIDE, WidgetSize.default(WidgetType.NEWS))
        assertEquals(WidgetSize.WIDE, WidgetSize.default(WidgetType.SUGGESTIONS))
    }

    @Test
    fun `default maps small types`() {
        assertEquals(WidgetSize.SMALL, WidgetSize.default(WidgetType.BLOCK))
        assertEquals(WidgetSize.SMALL, WidgetSize.default(WidgetType.FACTS))
        assertEquals(WidgetSize.SMALL, WidgetSize.default(WidgetType.WEATHER))
        assertEquals(WidgetSize.SMALL, WidgetSize.default(WidgetType.CALCULATOR))
    }

    @Test
    fun `serializes to ios compatible raw values`() {
        assertEquals("\"small\"", json.encodeToString(WidgetSize.SMALL))
        assertEquals("\"wide\"", json.encodeToString(WidgetSize.WIDE))
    }

    @Test
    fun `widget with position round trips size`() {
        val widget = WidgetWithPosition(type = WidgetType.BLOCK, position = 2, size = WidgetSize.SMALL)
        val decoded = json.decodeFromString<WidgetWithPosition>(json.encodeToString(widget))
        assertEquals(widget, decoded)
    }

    @Test
    fun `widget without size key defaults to wide for v60 compatibility`() {
        val legacy = """{"type":"PRICE","position":0}"""
        val decoded = json.decodeFromString<WidgetWithPosition>(legacy)
        assertEquals(WidgetSize.WIDE, decoded.size)
    }

    @Test
    fun `effective size forces suggestions wide`() {
        val suggestions = WidgetWithPosition(type = WidgetType.SUGGESTIONS, position = 0, size = WidgetSize.SMALL)
        assertEquals(WidgetSize.WIDE, suggestions.effectiveSize())

        val block = WidgetWithPosition(type = WidgetType.BLOCK, position = 1, size = WidgetSize.SMALL)
        assertEquals(WidgetSize.SMALL, block.effectiveSize())
    }

    @Test
    fun `default widget set matches v61 layout`() {
        val widgets = WidgetsData().widgets
        assertEquals(
            listOf(
                WidgetType.SUGGESTIONS,
                WidgetType.PRICE,
                WidgetType.BLOCK,
                WidgetType.FACTS,
                WidgetType.WEATHER,
                WidgetType.CALCULATOR,
                WidgetType.NEWS,
            ),
            widgets.map { it.type },
        )
        widgets.forEachIndexed { index, widget ->
            assertEquals(index, widget.position)
            assertEquals(WidgetSize.default(widget.type), widget.size)
        }
    }

    @Test
    fun `widgets data round trips through backup payload preserving sizes`() {
        val data = WidgetsData(
            widgets = listOf(
                WidgetWithPosition(WidgetType.PRICE, 0, WidgetSize.WIDE),
                WidgetWithPosition(WidgetType.BLOCK, 1, WidgetSize.SMALL),
            ),
        )
        val payload = WidgetsBackupV1(createdAt = 0L, widgets = data)
        val decoded = json.decodeFromString<WidgetsBackupV1>(json.encodeToString(payload))
        assertEquals(data.widgets, decoded.widgets.widgets)
        assertTrue(json.encodeToString(payload).contains("\"size\": \"small\""))
    }
}
