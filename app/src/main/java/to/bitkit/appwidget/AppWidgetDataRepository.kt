package to.bitkit.appwidget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.widgets.PriceService
import to.bitkit.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppWidgetDataRepository @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val priceService: PriceService,
) {
    suspend fun fetchPriceData(period: GraphPeriod = GraphPeriod.ONE_DAY): Result<PriceDTO> =
        withContext(ioDispatcher) {
            priceService.fetchData(period)
        }
}
