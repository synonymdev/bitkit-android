package to.bitkit.data.backup

import com.synonym.vssclient.vssDeriveStoreId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VssBackupClientLdkTest : BaseUnitTest() {

    private lateinit var sut: VssBackupClientLdk

    private val keychain = mock<Keychain>()

    @Before
    fun setUp() = runBlocking {
        sut = VssBackupClientLdk(
            ioDispatcher = testDispatcher,
            coordinator = VssClientCoordinator(),
            keychain = keychain,
        )
    }

    @Test
    fun `setup derives store id from the same captured credentials as the signer`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(TEST_MNEMONIC)
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn("test-passphrase")
        var setupArguments: List<Any?>? = null
        var deriveArguments: List<Any?>? = null
        mockStatic(Class.forName(VSS_FFI_CLASS)) { invocation ->
            when (invocation.method.name) {
                "vssDeriveStoreId" -> {
                    deriveArguments = invocation.arguments.toList()
                    "captured-store"
                }
                "vssNewLdkClientWithLnurlAuth" -> {
                    setupArguments = invocation.arguments.take(5)
                    null
                }
                else -> null
            }
        }.use {
            assertTrue(sut.setup().isSuccess)
            assertEquals(listOf(Env.vssStoreIdPrefix, TEST_MNEMONIC, "test-passphrase"), deriveArguments)
            assertEquals(
                listOf(Env.vssServerUrl, "captured-store", TEST_MNEMONIC, "test-passphrase", Env.lnurlAuthServerUrl),
                setupArguments,
            )
        }
    }

    @Test
    fun `setup succeeding after a failure leaves the client usable`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)
        assertIs<MnemonicNotAvailableException>(sut.setup().exceptionOrNull())

        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(TEST_MNEMONIC)

        mockStatic(Class.forName(VSS_FFI_CLASS)).use {
            whenever(vssDeriveStoreId(Env.vssStoreIdPrefix, TEST_MNEMONIC, null))
                .thenReturn("captured-store")
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
