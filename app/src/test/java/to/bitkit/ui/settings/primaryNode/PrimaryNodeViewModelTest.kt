package to.bitkit.ui.settings.primaryNode

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.models.PrimaryNode
import to.bitkit.models.PrimaryNodeError
import to.bitkit.repositories.PrimaryNodeRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimaryNodeViewModelTest : BaseUnitTest() {
    companion object {
        private const val KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    }

    private val context = mock<Context>()
    private val repo = mock<PrimaryNodeRepo>()
    private val savedUri = MutableStateFlow("")
    private val fundingPaused = MutableStateFlow(false)

    private fun viewModel(): PrimaryNodeViewModel {
        whenever(repo.primaryNodeUri).thenReturn(savedUri)
        whenever(repo.fundingPaused).thenReturn(fundingPaused)
        whenever(context.getString(R.string.primary_node__invalid)).thenReturn("Invalid node")
        whenever(context.getString(R.string.primary_node__connection_failed)).thenReturn("Connection failed")
        whenever(context.getString(R.string.primary_node__address_required)).thenReturn("Scan the full node URI")
        return PrimaryNodeViewModel(context, repo)
    }

    @Test
    fun `persisted funding pause is displayed until configuration resumes it`() = test {
        fundingPaused.value = true
        val vm = viewModel()
        assertTrue(vm.uiState.value.isFundingPaused)

        fundingPaused.value = false

        assertFalse(vm.uiState.value.isFundingPaused)
    }

    @Test
    fun `editing or scanning does not connect or overwrite configured state`() = test {
        savedUri.value = "$KEY@old.example:9735"
        val vm = viewModel()
        vm.onScan("lightning:$KEY@new.example:9735")

        assertEquals("$KEY@new.example:9735", vm.uiState.value.input)
        assertEquals(savedUri.value, vm.uiState.value.configuredUri)
        assertFalse(vm.uiState.value.isConnected)
        verify(repo, times(0)).configure(any())
    }

    @Test
    fun `invalid scan retains user input and gives actionable feedback`() = test {
        val vm = viewModel()
        vm.setInput(KEY)
        vm.onScan("bitcoin:bc1notanode")

        assertEquals(KEY, vm.uiState.value.input)
        assertEquals("Invalid node", vm.uiState.value.error)
    }

    @Test
    fun `connection error keeps previous primary and permits retry`() = test {
        savedUri.value = "$KEY@old.example:9735"
        whenever(repo.configure(any())).thenReturn(Result.failure(AppError("Connection failed")))
        val vm = viewModel()
        vm.setInput("$KEY@new.example:9735")
        vm.connect()

        assertEquals(savedUri.value, vm.uiState.value.configuredUri)
        assertEquals("Connection failed", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isConnected)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `unknown public key address asks for the full node URI`() = test {
        whenever(repo.configure(KEY)).thenReturn(Result.failure(PrimaryNodeError.AddressUnknown()))
        val vm = viewModel()
        vm.setInput(KEY)

        vm.connect()

        assertEquals("Scan the full node URI", vm.uiState.value.error)
        assertEquals(KEY, vm.uiState.value.input)
        assertFalse(vm.uiState.value.isConnected)
    }

    @Test
    fun `successful runtime configuration displays canonical resolved primary`() = test {
        val resolved = PrimaryNode(KEY, "node.example:9735")
        whenever(repo.configure(KEY)).thenReturn(Result.success(resolved))
        val vm = viewModel()
        vm.setInput(KEY)
        vm.connect()

        assertEquals(resolved.uri, vm.uiState.value.input)
        assertEquals(resolved.uri, vm.uiState.value.configuredUri)
        assertTrue(vm.uiState.value.isConnected)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `only one connection request runs at a time`() = test {
        val response = CompletableDeferred<Result<PrimaryNode>>()
        whenever(repo.configure(KEY)).doSuspendableAnswer { response.await() }
        val vm = viewModel()
        vm.setInput(KEY)
        vm.connect()
        vm.connect()

        assertTrue(vm.uiState.value.isLoading)
        response.complete(Result.success(PrimaryNode(KEY, "node.example:9735")))
        verify(repo, times(1)).configure(KEY)
    }
}
