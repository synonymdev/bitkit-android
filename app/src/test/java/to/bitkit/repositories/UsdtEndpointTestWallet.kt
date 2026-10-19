package to.bitkit.repositories

import com.synonym.bitkitcore.UsdtException
import com.synonym.bitkitcore.UsdtPaymentRequest
import com.synonym.bitkitcore.usdtAddress
import com.synonym.bitkitcore.usdtParsePaymentRequest
import kotlinx.coroutines.CoroutineDispatcher
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.services.UsdtService

internal class UsdtEndpointTestWallet(dispatcher: CoroutineDispatcher) : AutoCloseable {
    companion object {
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
        const val OTHER_ADDRESS = "0x2222222222222222222222222222222222222222"
    }

    var available = false
    var passphrase: String? = null
    var derivationAvailable = true
    private val keychain = mock<Keychain>()
    private val native = mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt"))
    val repo = UsdtRepo(UsdtService(keychain, mock()), dispatcher)

    init {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenAnswer {
            "test test test test test test test test test test test junk".takeIf { available }
        }
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenAnswer { passphrase }
        for ((phrase, address) in listOf(null to ADDRESS, "other wallet" to OTHER_ADDRESS)) {
            native.`when`<String> {
                usdtAddress("test test test test test test test test test test test junk", phrase)
            }.thenAnswer {
                if (!derivationAvailable) throw UsdtException.InvalidCredentials()
                address
            }
            native.`when`<UsdtPaymentRequest> { usdtParsePaymentRequest(address) }
                .thenReturn(UsdtPaymentRequest(address, null, null))
        }
    }

    override fun close() = native.close()
}
