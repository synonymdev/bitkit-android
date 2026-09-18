package to.bitkit.ui.settings.advanced

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServer
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.services.ElectrumProbeError
import to.bitkit.test.BaseUnitTest
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private val serverError = "Electrum Connection Failed"
    private val serverErrorDescription = "Bitkit could not establish a connection to Electrum."
    private val serverErrorNetwork = "This server is on a different Bitcoin network."
    private val serverErrorProtocol = "Secure connection failed."
    private val serverErrorCertificate = "This server's certificate is not trusted."
    private val serverUpdatedTitle = "Electrum Server Updated"
    private val serverUpdatedMessage = "Successfully connected to {host}:{port}"
    private val server = ElectrumServer(host = "example.com", tcp = 50001, ssl = 50002, protocol = ElectrumProtocol.SSL)

    @Before
    fun setUp() {
        whenever(context.getString(R.string.settings__es__error_host_port)).thenReturn(errorHostPort)
        whenever(context.getString(R.string.settings__es__error_host)).thenReturn(errorHost)
        whenever(context.getString(R.string.settings__es__error_port)).thenReturn(errorPort)
        whenever(context.getString(R.string.settings__es__error_port_invalid)).thenReturn(errorPortInvalid)
        whenever(context.getString(R.string.settings__es__error_invalid_http)).thenReturn(errorInvalidHttp)
        whenever(context.getString(R.string.settings__es__error_peer)).thenReturn(errorPeer)
        whenever(context.getString(R.string.settings__es__server_error)).thenReturn(serverError)
        whenever(context.getString(R.string.settings__es__server_error_description)).thenReturn(serverErrorDescription)
        whenever(context.getString(R.string.settings__es__server_error_network)).thenReturn(serverErrorNetwork)
        whenever(context.getString(R.string.settings__es__server_error_protocol)).thenReturn(serverErrorProtocol)
        whenever(context.getString(R.string.settings__es__server_error_certificate))
            .thenReturn(serverErrorCertificate)
        whenever(context.getString(R.string.settings__es__server_updated_title)).thenReturn(serverUpdatedTitle)
        whenever(context.getString(R.string.settings__es__server_updated_message)).thenReturn(serverUpdatedMessage)
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

    @Test
    fun `connectToServer shows network toast on network mismatch`() = test {
        val error = ElectrumProbeError.NetworkMismatch(server, expected = "regtest", actual = "bitcoin")

        val toast = connectAndCollectToast(Result.failure(error))

        assertErrorToast(serverErrorNetwork, toast)
    }

    @Test
    fun `connectToServer shows protocol toast on protocol mismatch`() = test {
        val error = ElectrumProbeError.ProtocolMismatch(server, AppError("handshake"))

        val toast = connectAndCollectToast(Result.failure(error))

        assertErrorToast(serverErrorProtocol, toast)
    }

    @Test
    fun `connectToServer shows certificate toast on untrusted certificate`() = test {
        val error = ElectrumProbeError.UntrustedCertificate(server, AppError("PKIX path building failed"))

        val toast = connectAndCollectToast(Result.failure(error))

        assertErrorToast(serverErrorCertificate, toast)
    }

    @Test
    fun `connectToServer shows generic toast when server is unreachable`() = test {
        val error = ElectrumProbeError.Unreachable(server, AppError("timeout"))

        val toast = connectAndCollectToast(Result.failure(error))

        assertErrorToast(serverErrorDescription, toast)
    }

    @Test
    fun `connectToServer shows generic toast when server is not electrum`() = test {
        val error = ElectrumProbeError.NotElectrum(server)

        val toast = connectAndCollectToast(Result.failure(error))

        assertErrorToast(serverErrorDescription, toast)
    }

    @Test
    fun `connectToServer shows generic toast on other failures`() = test {
        val toast = connectAndCollectToast(Result.failure(AppError("node failed to start")))

        assertErrorToast(serverErrorDescription, toast)
    }

    @Test
    fun `connectToServer shows updated toast on success`() = test {
        val toast = connectAndCollectToast(Result.success(Unit))

        assertEquals(Toast.ToastType.SUCCESS, toast.type)
        assertEquals(serverUpdatedTitle, toast.title)
        assertEquals("Successfully connected to example.com:50002", toast.description)
        assertEquals("ElectrumUpdatedToast", toast.testTag)
        assertFalse(sut.uiState.value.isLoading)
        assertFalse(sut.uiState.value.hasEdited)
    }

    private suspend fun TestScope.connectAndCollectToast(result: Result<Unit>): Toast {
        whenever(lightningRepo.restartWithElectrumServer("ssl://example.com:50002")).thenReturn(result)
        sut = createSut()
        advanceUntilIdle()
        sut.setProtocol(ElectrumProtocol.SSL)
        sut.setHost("example.com")
        sut.setPort("50002")

        val toasts = mutableListOf<Toast>()
        val collectJob = launch { ToastEventBus.events.collect { toasts.add(it) } }
        advanceUntilIdle()
        sut.connectToServer()
        advanceUntilIdle()
        collectJob.cancel()

        return toasts.single()
    }

    private fun assertErrorToast(expectedDescription: String, toast: Toast) {
        assertEquals(Toast.ToastType.WARNING, toast.type)
        assertEquals(serverError, toast.title)
        assertEquals(expectedDescription, toast.description)
        assertEquals("ElectrumErrorToast", toast.testTag)
        assertFalse(sut.uiState.value.isLoading)
    }
}
