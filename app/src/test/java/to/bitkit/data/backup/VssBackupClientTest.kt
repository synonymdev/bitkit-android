package to.bitkit.data.backup

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
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
    fun `setup returns false when mnemonic is not available`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        val result = sut.setup()

        assertFalse(result)
    }

    @Test
    fun `setup does not call vssStoreIdProvider when mnemonic is not available`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        sut.setup()

        verify(vssStoreIdProvider, never()).getVssStoreId(any())
    }

    @Test
    fun `setup checks mnemonic before proceeding with vss initialization`() = test {
        val testMnemonic = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(testMnemonic)
        whenever(vssStoreIdProvider.getVssStoreId(any())).thenReturn("test-store-id")

        // Setup will fail on native VSS calls, but we verify we passed the mnemonic check
        runCatching { sut.setup() }

        verify(vssStoreIdProvider).getVssStoreId(any())
    }

    @Test
    fun `setup can be called multiple times when mnemonic not available`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        // Multiple calls should all return false without crashing
        assertFalse(sut.setup())
        assertFalse(sut.setup())
        assertFalse(sut.setup())
    }
}
