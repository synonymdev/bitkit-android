package to.bitkit.ui.settings.advanced

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
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
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElectrumConfigViewModelTest : BaseUnitTest() {
    private val context: Context = mock()
    private val settingsStore: SettingsStore = mock()
    private val lightningRepo: LightningRepo = mock()

    private lateinit var sut: ElectrumConfigViewModel

    private val errorHostPort = "Please specify a host and port to connect to."
    private val errorHost = "Please specify a host to connect to."
    private val errorPort = "Please specify a port to connect to."
    private val errorPortInvalid = "Please specify a valid port."
    private val errorInvalidHttp = "Please specify a valid url."
    private val errorPeer = "Electrum Error"

    @Before
    fun setUp() {
        whenever(context.getString(R.string.settings__es__error_host_port)).thenReturn(errorHostPort)
        whenever(context.getString(R.string.settings__es__error_host)).thenReturn(errorHost)
        whenever(context.getString(R.string.settings__es__error_port)).thenReturn(errorPort)
        whenever(context.getString(R.string.settings__es__error_port_invalid)).thenReturn(errorPortInvalid)
        whenever(context.getString(R.string.settings__es__error_invalid_http)).thenReturn(errorInvalidHttp)
        whenever(context.getString(R.string.settings__es__error_peer)).thenReturn(errorPeer)
        whenever(settingsStore.data).thenReturn(
            flowOf(SettingsData(electrumServer = "ssl://electrum.blockstream.info:50002"))
        )
        whenever(lightningRepo.lightningState).thenReturn(MutableStateFlow(LightningState()))
    }

    private fun createSut(): ElectrumConfigViewModel = ElectrumConfigViewModel(
        bgDispatcher = testDispatcher,
        context = context,
        settingsStore = settingsStore,
        lightningRepo = lightningRepo,
    )

    @Test
    fun `validateInput returns null for valid host and port`() = test {
        sut = createSut()
        advanceUntilIdle()

        assertNull(sut.validateInput(host = "electrum.blockstream.info", port = "50002"))
    }

    @Test
    fun `validateInput returns null for ip host`() = test {
        sut = createSut()
        advanceUntilIdle()

        assertNull(sut.validateInput(host = "192.168.1.1", port = "50002"))
    }

    @Test
    fun `validateInput returns null for local host`() = test {
        sut = createSut()
        advanceUntilIdle()

        assertNull(sut.validateInput(host = "umbrel.local", port = "50002"))
    }

    @Test
    fun `validateInput returns error for dotless host`() = test {
        sut = createSut()
        advanceUntilIdle()

        assertNotNull(sut.validateInput(host = "notahost", port = "50002"))
    }

    @Test
    fun `onClickConnect does not hang on pathological dotless host`() = test {
        sut = createSut()
        advanceUntilIdle()
        sut.setHost("a".repeat(64))
        sut.setPort("50002")

        withTimeout(2.seconds) {
            sut.onClickConnect()
            advanceUntilIdle()
        }

        verify(lightningRepo, never()).restartWithElectrumServer(any())
    }
}
