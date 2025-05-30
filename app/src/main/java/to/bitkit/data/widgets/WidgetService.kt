package to.bitkit.data.widgets

import to.bitkit.models.WidgetType

interface WidgetService<T> {
    val widgetType: WidgetType
    suspend fun fetchData(): Result<T>
    val refreshInterval: kotlin.time.Duration
}
