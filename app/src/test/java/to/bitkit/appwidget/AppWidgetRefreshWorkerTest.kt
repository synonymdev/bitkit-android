package to.bitkit.appwidget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.appwidget.model.AppWidgetRefreshMetadata
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.data.dto.ArticleDTO
import to.bitkit.data.dto.BlockDTO
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.PublisherDTO
import to.bitkit.data.dto.WeatherDTO
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AppWidgetRefreshWorkerTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dataRepository = mock<AppWidgetDataRepository>()
    private val preferencesStore = mock<AppWidgetPreferencesStore>()
    private val appWidgetUpdater = mock<AppWidgetUpdater>()
    private val clock = mock<Clock>()
    private val workerParameters = mock<WorkerParameters>()

    @Before
    fun setUp() {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(NOW_MS))
        whenever(workerParameters.inputData).thenReturn(
            workDataOf(AppWidgetRefreshScheduler.WORK_INPUT_REASON to AppWidgetRefreshReason.APP_START.name),
        )
    }

    @Test
    fun `fresh remote widget type skips network refresh`() = test {
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.HEADLINES))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 1.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 1.minutes.inWholeMilliseconds,
            ),
        )

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(dataRepository, never()).fetchArticles()
        verify(preferencesStore, never()).markRefreshAttempt(any(), any())
        verify(preferencesStore, never()).markRefreshSuccess(any(), any())
        verify(appWidgetUpdater).update(AppWidgetType.HEADLINES, context)
    }

    @Test
    fun `fresh price widget refreshes uncached active period`() = test {
        val period = GraphPeriod.ONE_WEEK
        val price = price(period)
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.PRICE))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.PRICE)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 1.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 1.minutes.inWholeMilliseconds,
            ),
        )
        whenever(preferencesStore.getUncachedActivePricePeriods()).thenReturn(setOf(period))
        whenever(preferencesStore.getActivePricePeriods()).thenReturn(setOf(period))
        whenever(dataRepository.fetchPriceData(period)).thenReturn(Result.success(price))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.PRICE, NOW_MS)
        verify(dataRepository).fetchPriceData(period)
        verify(preferencesStore).cachePriceData(period, price)
        verify(preferencesStore).markRefreshSuccess(AppWidgetType.PRICE, NOW_MS)
        verify(appWidgetUpdater).update(AppWidgetType.PRICE, context)
    }

    @Test
    fun `connectivity remote refresh failure retries and updates cached widget`() = test {
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.HEADLINES))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
            ),
        )
        whenever(dataRepository.fetchArticles()).thenReturn(Result.failure(UnknownHostException("dns")))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.HEADLINES, NOW_MS)
        verify(dataRepository).fetchArticles()
        verify(preferencesStore, never()).markRefreshSuccess(any(), any())
        verify(appWidgetUpdater).update(AppWidgetType.HEADLINES, context)
    }

    @Test
    fun `non-connectivity remote refresh failure updates cached widget without retry`() = test {
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.HEADLINES))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
            ),
        )
        whenever(dataRepository.fetchArticles()).thenReturn(Result.failure(AppWidgetRefreshWorkerTestError("failed")))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.HEADLINES, NOW_MS)
        verify(dataRepository).fetchArticles()
        verify(preferencesStore, never()).markRefreshSuccess(any(), any())
        verify(appWidgetUpdater).update(AppWidgetType.HEADLINES, context)
    }

    @Test
    fun `recent failed alarm refresh backs off without fetching remote data`() = test {
        whenever(workerParameters.inputData).thenReturn(
            workDataOf(AppWidgetRefreshScheduler.WORK_INPUT_REASON to AppWidgetRefreshReason.CATCH_UP_ALARM.name),
        )
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.HEADLINES))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 1.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 16.minutes.inWholeMilliseconds,
            ),
        )

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(dataRepository, never()).fetchArticles()
        verify(preferencesStore, never()).markRefreshAttempt(any(), any())
        verify(preferencesStore, never()).markRefreshSuccess(any(), any())
        verify(appWidgetUpdater).update(AppWidgetType.HEADLINES, context)
    }

    @Test
    fun `price connectivity refresh failure retries and updates cached widget`() = test {
        val period = GraphPeriod.ONE_DAY
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.PRICE))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.PRICE)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
            ),
        )
        whenever(preferencesStore.getUncachedActivePricePeriods()).thenReturn(emptySet())
        whenever(preferencesStore.getActivePricePeriods()).thenReturn(setOf(period))
        whenever(dataRepository.fetchPriceData(period)).thenReturn(Result.failure(UnknownHostException("dns")))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.PRICE, NOW_MS)
        verify(dataRepository).fetchPriceData(period)
        verify(preferencesStore, never()).markRefreshSuccess(any(), any())
        verify(appWidgetUpdater).update(AppWidgetType.PRICE, context)
    }

    @Test
    fun `connectivity failure continues remaining remote refreshes before retry`() = test {
        val firstPeriod = GraphPeriod.ONE_DAY
        val secondPeriod = GraphPeriod.ONE_WEEK
        val article = article()
        val block = block()
        val weather = weather()
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(AppWidgetType.entries.toSet())
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.PRICE)).thenReturn(metadata(minutesAgo = 61))
        whenever(preferencesStore.getUncachedActivePricePeriods()).thenReturn(emptySet())
        whenever(preferencesStore.getActivePricePeriods()).thenReturn(linkedSetOf(firstPeriod, secondPeriod))
        whenever(dataRepository.fetchPriceData(firstPeriod)).thenReturn(Result.failure(UnknownHostException("dns")))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(metadata(minutesAgo = 61))
        whenever(dataRepository.fetchArticles()).thenReturn(Result.success(listOf(article)))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.BLOCKS)).thenReturn(metadata(minutesAgo = 61))
        whenever(dataRepository.fetchBlock()).thenReturn(Result.success(block))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.WEATHER)).thenReturn(metadata(minutesAgo = 31))
        whenever(dataRepository.fetchWeather()).thenReturn(Result.success(weather))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
        verify(dataRepository).fetchPriceData(firstPeriod)
        verify(dataRepository, never()).fetchPriceData(secondPeriod)
        verify(dataRepository).fetchArticles()
        verify(dataRepository).fetchBlock()
        verify(dataRepository).fetchWeather()
        verify(dataRepository, never()).fetchFacts()
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.PRICE, NOW_MS)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.HEADLINES, NOW_MS)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.BLOCKS, NOW_MS)
        verify(preferencesStore).markRefreshAttempt(AppWidgetType.WEATHER, NOW_MS)
        verify(preferencesStore).cacheArticlesAndRotate(listOf(article))
        verify(preferencesStore).cacheBlock(block)
        verify(preferencesStore).cacheWeather(weather)
        verify(preferencesStore).markRefreshSuccess(AppWidgetType.HEADLINES, NOW_MS)
        verify(preferencesStore).markRefreshSuccess(AppWidgetType.BLOCKS, NOW_MS)
        verify(preferencesStore).markRefreshSuccess(AppWidgetType.WEATHER, NOW_MS)
        verify(preferencesStore, never()).markRefreshSuccess(AppWidgetType.PRICE, NOW_MS)
        verify(appWidgetUpdater).update(AppWidgetType.PRICE, context)
        verify(appWidgetUpdater).update(AppWidgetType.HEADLINES, context)
        verify(appWidgetUpdater).update(AppWidgetType.BLOCKS, context)
        verify(appWidgetUpdater).update(AppWidgetType.WEATHER, context)
        verify(appWidgetUpdater, never()).update(AppWidgetType.FACTS, context)
    }

    @Test
    fun `remote refresh cancellation is rethrown`() = test {
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.HEADLINES))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
            ),
        )
        whenever(dataRepository.fetchArticles()).thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            worker().doWork()
        }

        verify(preferencesStore).markRefreshAttempt(AppWidgetType.HEADLINES, NOW_MS)
        verify(appWidgetUpdater, never()).update(any(), any())
    }

    @Test
    fun `remote refresh result cancellation is rethrown`() = test {
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.HEADLINES))
        whenever(preferencesStore.getRefreshMetadata(AppWidgetType.HEADLINES)).thenReturn(
            AppWidgetRefreshMetadata(
                lastAttemptAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
                lastSuccessAtMs = NOW_MS - 61.minutes.inWholeMilliseconds,
            ),
        )
        whenever(dataRepository.fetchArticles()).thenReturn(Result.failure(CancellationException("cancelled")))

        assertFailsWith<CancellationException> {
            worker().doWork()
        }

        verify(preferencesStore).markRefreshAttempt(AppWidgetType.HEADLINES, NOW_MS)
        verify(appWidgetUpdater, never()).update(any(), any())
    }

    @Test
    fun `facts refresh rotates locally and does not mark remote success`() = test {
        val facts = listOf("Bitcoin does not have a CEO.")
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.FACTS))
        whenever(workerParameters.inputData).thenReturn(
            workDataOf(
                AppWidgetRefreshScheduler.WORK_INPUT_REASON to AppWidgetRefreshReason.FACTS_LOCAL_REFRESH.name,
            ),
        )
        whenever(dataRepository.fetchFacts()).thenReturn(Result.success(facts))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(dataRepository).fetchFacts()
        verify(preferencesStore).cacheFacts(facts)
        verify(preferencesStore).bumpFactsRotationTick()
        verify(preferencesStore, never()).getRefreshMetadata(any())
        verify(preferencesStore, never()).markRefreshAttempt(any(), any())
        verify(preferencesStore, never()).markRefreshSuccess(any(), any())
        verify(appWidgetUpdater).update(AppWidgetType.FACTS, context)
    }

    @Test
    fun `remote refresh reason skips facts because local work handles it`() = test {
        whenever(preferencesStore.getActiveWidgetTypes()).thenReturn(setOf(AppWidgetType.FACTS))

        val result = worker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        verify(dataRepository, never()).fetchFacts()
        verify(preferencesStore, never()).bumpFactsRotationTick()
        verify(appWidgetUpdater, never()).update(any(), any())
    }

    private fun worker(): AppWidgetRefreshWorker =
        AppWidgetRefreshWorker(
            appContext = context,
            workerParams = workerParameters,
            dataRepository = dataRepository,
            preferencesStore = preferencesStore,
            appWidgetUpdater = appWidgetUpdater,
            clock = clock,
        )

    private fun metadata(minutesAgo: Long) = AppWidgetRefreshMetadata(
        lastAttemptAtMs = NOW_MS - minutesAgo.minutes.inWholeMilliseconds,
        lastSuccessAtMs = NOW_MS - minutesAgo.minutes.inWholeMilliseconds,
    )

    private fun price(period: GraphPeriod) = PriceDTO(
        widgets = listOf(
            PriceWidgetData(
                pair = TradingPair.BTC_USD,
                period = period,
                change = Change(isPositive = false, formatted = "-1%"),
                price = "\$1",
                pastValues = listOf(1.0),
            ),
        ),
    )

    private fun article() = ArticleDTO(
        title = "Bitkit",
        publishedDate = "2026-06-05",
        link = "https://bitkit.to",
        publisher = PublisherDTO(title = "Bitkit"),
    )

    private fun block() = BlockDTO(
        hash = "hash",
        height = "1",
        timestamp = NOW_MS,
        transactionCount = "1",
        size = "1",
        weight = "1",
        difficulty = "1",
        merkleRoot = "root",
    )

    private fun weather() = WeatherDTO(
        condition = FeeCondition.GOOD,
        currentFee = "1",
        nextBlockFee = 1,
    )

    private companion object {
        const val NOW_MS = 10_000_000L
    }
}

private class AppWidgetRefreshWorkerTestError(message: String) : AppError(message)
