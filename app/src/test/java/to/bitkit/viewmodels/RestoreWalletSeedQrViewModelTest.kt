package to.bitkit.viewmodels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.models.QrCodePayload
import to.bitkit.repositories.SeedQrRepo
import to.bitkit.services.core.Bip39Service
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreWalletSeedQrViewModelTest : BaseUnitTest() {
    private val bip39Service = mock<Bip39Service>()
    private val seedQrRepo = mock<SeedQrRepo>()

    private lateinit var viewModel: RestoreWalletViewModel

    @Before
    fun setup() {
        whenever { bip39Service.isValidWord(any()) }.thenReturn(true)
        whenever(bip39Service.isValidMnemonicSize(any())).thenReturn(true)
        whenever { bip39Service.validateMnemonic(any()) }.thenReturn(Result.success(Unit))
        viewModel = RestoreWalletViewModel(bip39Service, seedQrRepo)
    }

    @Test
    fun `seedqr scan should replace words with decoded mnemonic`() = test {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val payload = QrCodePayload(text = "seedqr", rawBytes = null)
        whenever(seedQrRepo.decode(payload)).thenReturn(Result.success(mnemonic))

        viewModel.onSeedQrScan(payload)
        advanceUntilIdle()

        assertEquals(mnemonic.split(" "), viewModel.uiState.value.words.take(12))
        assertFalse(viewModel.uiState.value.is24Words)
        assertTrue(viewModel.uiState.value.areButtonsEnabled)
    }

    @Test
    fun `invalid seedqr scan should keep words and emit error`() = test {
        val payload = QrCodePayload(text = "invalid", rawBytes = null)
        whenever(seedQrRepo.decode(payload)).thenReturn(Result.failure(IllegalArgumentException()))
        val effect = async { viewModel.effects.first() }

        viewModel.onSeedQrScan(payload)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.words.all { it.isEmpty() })
        assertIs<RestoreWalletEffect.InvalidSeedQr>(effect.await())
    }
}
