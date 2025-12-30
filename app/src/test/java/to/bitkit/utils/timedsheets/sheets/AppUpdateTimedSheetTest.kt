package to.bitkit.utils.timedsheets.sheets

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.BuildConfig
import to.bitkit.data.dto.PlatformDetails
import to.bitkit.data.dto.Platforms
import to.bitkit.data.dto.ReleaseInfoDTO
import to.bitkit.services.AppUpdaterService
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.components.TimedSheetType

class AppUpdateTimedSheetTest : BaseUnitTest() {
    private lateinit var appUpdaterService: AppUpdaterService
    private lateinit var bgDispatcher: CoroutineDispatcher
    private lateinit var sut: AppUpdateTimedSheet

    @Before
    fun setUp() {
        appUpdaterService = mock()
        bgDispatcher = StandardTestDispatcher()
        sut = AppUpdateTimedSheet(appUpdaterService, bgDispatcher)
    }

    @Test
    fun `type is APP_UPDATE`() {
        assertTrue(sut.type == TimedSheetType.APP_UPDATE)
    }

    @Test
    fun `priority is 5`() {
        assertTrue(sut.priority == 5)
    }

    @Test
    fun `should not show when build number is same or lower`() = test {
        val releaseInfo = ReleaseInfoDTO(
            platforms = Platforms(
                android = PlatformDetails(
                    version = "1.0.0",
                    buildNumber = BuildConfig.VERSION_CODE,
                    notes = "Test release",
                    pubDate = "2024-01-01",
                    url = "https://example.com",
                    isCritical = false
                ),
                ios = null
            )
        )
        whenever(appUpdaterService.getReleaseInfo()).thenReturn(releaseInfo)

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should not show when update is critical`() = test {
        val releaseInfo = ReleaseInfoDTO(
            platforms = Platforms(
                android = PlatformDetails(
                    version = "1.0.0",
                    buildNumber = BuildConfig.VERSION_CODE + 1,
                    notes = "Test release",
                    pubDate = "2024-01-01",
                    url = "https://example.com",
                    isCritical = true
                ),
                ios = null
            )
        )
        whenever(appUpdaterService.getReleaseInfo()).thenReturn(releaseInfo)

        val result = sut.shouldShow()

        assertFalse(result)
    }

    @Test
    fun `should show when non-critical update available`() = test {
        val releaseInfo = ReleaseInfoDTO(
            platforms = Platforms(
                android = PlatformDetails(
                    version = "1.0.0",
                    buildNumber = BuildConfig.VERSION_CODE + 1,
                    notes = "Test release",
                    pubDate = "2024-01-01",
                    url = "https://example.com",
                    isCritical = false
                ),
                ios = null
            )
        )
        whenever(appUpdaterService.getReleaseInfo()).thenReturn(releaseInfo)

        val result = sut.shouldShow()

        assertTrue(result)
    }

    @Test
    fun `should not show when network error occurs`() = test {
        whenever(appUpdaterService.getReleaseInfo()).thenThrow(RuntimeException("Network error"))

        val result = sut.shouldShow()

        assertFalse(result)
    }
}
