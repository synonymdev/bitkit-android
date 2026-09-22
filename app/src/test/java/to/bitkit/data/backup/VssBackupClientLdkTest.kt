package to.bitkit.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VssBackupClientLdkTest : BaseUnitTest() {

    private lateinit var sut: VssBackupClientLdk

    private val vssStoreIdProvider = mock<VssStoreIdProvider>()
    private val keychain = mock<Keychain>()

    @Before
    fun setUp() = runBlocking {
        sut = VssBackupClientLdk(
            ioDispatcher = testDispatcher,
            vssStoreIdProvider = vssStoreIdProvider,
            keychain = keychain,
        )
    }

    @Test
    fun `setup succeeding after a failure leaves the client usable`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)
        assertIs<MnemonicNotAvailableException>(sut.setup().exceptionOrNull())

        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(TEST_MNEMONIC)
        whenever(vssStoreIdProvider.getVssStoreId(any())).thenReturn("test-store-id")

        mockStatic(Class.forName(VSS_FFI_CLASS)).use {
            assertTrue(sut.setup().isSuccess)

            val result = sut.getObject("key")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }
    }

    companion object {
        private const val VSS_FFI_CLASS = "com.synonym.vssclient.Vss_rust_client_ffiKt"
        private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
    }
}
