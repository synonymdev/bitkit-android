package to.bitkit.data.backup

import com.synonym.vssclient.VssItem
import com.synonym.vssclient.vssStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VssBackupClientTest : BaseUnitTest() {

    private lateinit var sut: VssBackupClient

    private val vssStoreIdProvider = mock<VssStoreIdProvider>()
    private val keychain = mock<Keychain>()

    @Before
    fun setUp() = runBlocking {
        sut = VssBackupClient(
            ioDispatcher = testDispatcher,
            vssStoreIdProvider = vssStoreIdProvider,
            keychain = keychain,
        )
    }

    @Test
    fun `setup fails with MnemonicNotAvailableException when mnemonic is not available`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        val result = sut.setup()

        assertTrue(result.isFailure)
        assertIs<MnemonicNotAvailableException>(result.exceptionOrNull())
    }

    @Test
    fun `setup does not call vssStoreIdProvider when mnemonic is not available`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        sut.setup()

        verify(vssStoreIdProvider, never()).getVssStoreId(any())
    }

    @Test
    fun `setup checks mnemonic before proceeding with vss initialization`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(TEST_MNEMONIC)
        whenever(vssStoreIdProvider.getVssStoreId(any())).thenReturn("test-store-id")

        // Setup will fail on native VSS calls, but we verify we passed the mnemonic check
        runCatching { sut.setup() }

        verify(vssStoreIdProvider).getVssStoreId(any())
    }

    @Test
    fun `setup can be called multiple times when mnemonic not available`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        // Multiple calls should all fail with MnemonicNotAvailableException without crashing
        assertIs<MnemonicNotAvailableException>(sut.setup().exceptionOrNull())
        assertIs<MnemonicNotAvailableException>(sut.setup().exceptionOrNull())
        assertIs<MnemonicNotAvailableException>(sut.setup().exceptionOrNull())
    }

    @Test
    fun `setup succeeding after a failure leaves the client usable`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)
        assertIs<MnemonicNotAvailableException>(sut.setup().exceptionOrNull())

        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(TEST_MNEMONIC)
        whenever(vssStoreIdProvider.getVssStoreId(any())).thenReturn("test-store-id")

        mockStatic(Class.forName(VSS_FFI_CLASS)).use {
            assertTrue(sut.setup().isSuccess)

            val result = sut.getObject("METADATA")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }
    }

    @Test
    fun `setupWithRetry succeeding after a failure leaves the client usable`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name))
            .thenReturn(null)
            .thenReturn(TEST_MNEMONIC)
        whenever(vssStoreIdProvider.getVssStoreId(any())).thenReturn("test-store-id")

        mockStatic(Class.forName(VSS_FFI_CLASS)).use {
            assertTrue(sut.setupWithRetry(baseDelayMs = 0L) {}.isSuccess)

            val item = VssItem("METADATA", byteArrayOf(), 1L)
            whenever(vssStore(any(), any())).thenReturn(item)

            assertEquals(item, sut.putObject("METADATA", byteArrayOf()).getOrNull())
        }
    }

    companion object {
        private const val VSS_FFI_CLASS = "com.synonym.vssclient.Vss_rust_client_ffiKt"
        private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
    }
}
