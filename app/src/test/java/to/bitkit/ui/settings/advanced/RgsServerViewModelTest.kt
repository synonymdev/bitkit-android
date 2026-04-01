package to.bitkit.ui.settings.advanced

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.LightningRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RgsServerViewModelTest : BaseUnitTest() {
    private val settingsStore: SettingsStore = mock()
    private val lightningRepo: LightningRepo = mock()

    private lateinit var sut: RgsServerViewModel

    private val defaultRgsUrl = "https://rgs.blocktank.to/snapshot"

    @Before
    fun setUp() {
        whenever(settingsStore.data).thenReturn(
            flowOf(SettingsData(rgsServerUrl = defaultRgsUrl))
        )
    }

    private fun createSut(): RgsServerViewModel = RgsServerViewModel(
        bgDispatcher = testDispatcher,
        settingsStore = settingsStore,
        lightningRepo = lightningRepo,
    )

    @Test
    fun `initial state loads rgsServerUrl from settings`() = test {
        sut = createSut()

        sut.uiState.test {
            val state = awaitItem()
            assertEquals(defaultRgsUrl, state.connectedRgsUrl)
            assertEquals(defaultRgsUrl, state.rgsUrl)
            assertFalse(state.hasEdited)
            assertFalse(state.canConnect)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setRgsUrl updates url and computes canConnect`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.setRgsUrl("https://other.server.com/snapshot")

        val state = sut.uiState.value
        assertEquals("https://other.server.com/snapshot", state.rgsUrl)
        assertTrue(state.hasEdited)
        assertTrue(state.canConnect)
    }

    @Test
    fun `setRgsUrl trims whitespace`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.setRgsUrl("  https://other.server.com/snapshot  ")

        assertEquals("https://other.server.com/snapshot", sut.uiState.value.rgsUrl)
    }

    @Test
    fun `canConnect is false when url matches connected url`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.setRgsUrl(defaultRgsUrl)

        assertFalse(sut.uiState.value.canConnect)
    }

    @Test
    fun `canConnect is false when url is blank`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.setRgsUrl("   ")

        assertFalse(sut.uiState.value.canConnect)
    }

    @Test
    fun `canConnect is false when url is invalid`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.setRgsUrl("not-a-url")

        assertFalse(sut.uiState.value.canConnect)
    }

    @Test
    fun `onClickConnect does nothing for blank url`() = test {
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl("")

        sut.onClickConnect()
        advanceUntilIdle()

        verify(lightningRepo, never()).restartWithRgsServer("")
        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `onClickConnect does nothing for invalid url`() = test {
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl("invalid")

        sut.onClickConnect()
        advanceUntilIdle()

        assertFalse(sut.uiState.value.isLoading)
    }

    @Test
    fun `onClickConnect success sets connectionResult success`() = test {
        val newUrl = "https://other.server.com/snapshot"
        whenever(lightningRepo.restartWithRgsServer(newUrl))
            .thenReturn(Result.success(Unit))
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl(newUrl)

        sut.onClickConnect()
        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isLoading)
        val result = assertNotNull(state.connectionResult)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `onClickConnect failure sets connectionResult failure`() = test {
        val newUrl = "https://other.server.com/snapshot"
        val error = Exception("Connection failed")
        whenever(lightningRepo.restartWithRgsServer(newUrl))
            .thenReturn(Result.failure(error))
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl(newUrl)

        sut.onClickConnect()
        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isLoading)
        val result = assertNotNull(state.connectionResult)
        assertTrue(result.isFailure)
        assertEquals("Connection failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `onClickConnect with invalid host sets connectionResult failure`() = test {
        val newUrl = "https://rapidsync.lightningdevkit/snapshot"
        whenever(lightningRepo.restartWithRgsServer(newUrl))
            .thenReturn(Result.failure(Exception("Failed to start node")))
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl(newUrl)

        sut.onClickConnect()
        advanceUntilIdle()

        val state = sut.uiState.value
        assertFalse(state.isLoading)
        val result = assertNotNull(state.connectionResult)
        assertTrue(result.isFailure)
        assertEquals("Failed to start node", result.exceptionOrNull()?.message)
    }

    @Test
    fun `clearConnectionResult resets connectionResult to null`() = test {
        val newUrl = "https://other.server.com/snapshot"
        whenever(lightningRepo.restartWithRgsServer(newUrl))
            .thenReturn(Result.success(Unit))
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl(newUrl)
        sut.onClickConnect()
        advanceUntilIdle()

        sut.clearConnectionResult()

        assertNull(sut.uiState.value.connectionResult)
    }

    @Test
    fun `onScan delegates to setRgsUrl`() = test {
        sut = createSut()
        advanceUntilIdle()

        sut.onScan("https://scanned.server.com/snapshot")

        assertEquals("https://scanned.server.com/snapshot", sut.uiState.value.rgsUrl)
    }

    @Test
    fun `resetToDefault sets url to env default`() = test {
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl("https://custom.server.com/snapshot")

        sut.resetToDefault()

        val state = sut.uiState.value
        assertFalse(state.canReset)
    }

    @Test
    fun `isLoading is true while connecting`() = test {
        val newUrl = "https://other.server.com/snapshot"
        whenever(lightningRepo.restartWithRgsServer(newUrl))
            .thenReturn(Result.success(Unit))
        sut = createSut()
        advanceUntilIdle()
        sut.setRgsUrl(newUrl)

        sut.uiState.test {
            skipItems(1)
            sut.onClickConnect()
            val loadingState = awaitItem()
            assertTrue(loadingState.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
