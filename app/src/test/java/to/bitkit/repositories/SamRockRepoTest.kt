package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.AccountType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.Before
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.SamRockPaymentMethod
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.services.CoreService
import to.bitkit.services.OnchainService
import to.bitkit.test.BaseUnitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamRockRepoTest : BaseUnitTest() {
    companion object {
        private const val DESCRIPTOR = "wpkh([f23f9fd2/86'/1'/0']tpub.../0/*)"
        private const val INVALID_RESPONSE = "Invalid setup response."
        private const val MISSING_MNEMONIC = "Bitkit could not read your recovery phrase."
        private const val MISSING_RESULT = "The store did not return a Bitcoin setup result."
        private const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        private const val PASSPHRASE = "wallet passphrase"
        private const val POST_URL =
            "https://btcpay.example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=secret"
        private const val REJECTED_DESCRIPTOR = "The store rejected the Bitcoin descriptor."
        private const val REQUEST_ERROR = "Could not prepare the setup request."
        private const val SETUP_FAILED = "Setup failed."
        private const val SUCCESS_RESPONSE = """{"Success":true,"Result":{"Results":{"BTC":{"Success":true}}}}"""
        private const val UNSUPPORTED_SETUP = "This QR does not request Bitcoin on-chain setup."
    }

    private val context = mock<Context>()
    private val keychain = mock<Keychain>()
    private val settingsStore = mock<SettingsStore>()
    private val coreService = mock<CoreService>()
    private val onchainService = mock<OnchainService>()
    private val samRockHttpClient = mock<SamRockHttpClient>()
    private lateinit var sut: SamRockRepo

    @Before
    fun setUp() {
        whenever(context.getString(R.string.btcpay__invalid_response)).thenReturn(INVALID_RESPONSE)
        whenever(context.getString(R.string.btcpay__missing_mnemonic)).thenReturn(MISSING_MNEMONIC)
        whenever(context.getString(R.string.btcpay__missing_result)).thenReturn(MISSING_RESULT)
        whenever(context.getString(R.string.btcpay__rejected_descriptor)).thenReturn(REJECTED_DESCRIPTOR)
        whenever(context.getString(R.string.btcpay__request_error)).thenReturn(REQUEST_ERROR)
        whenever(context.getString(R.string.btcpay__setup_failed)).thenReturn(SETUP_FAILED)
        whenever(context.getString(R.string.btcpay__unsupported_text)).thenReturn(UNSUPPORTED_SETUP)
        whenever(coreService.onchain).thenReturn(onchainService)
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(selectedAddressType = "taproot")))
        sut = SamRockRepo(
            context = context,
            ioDispatcher = testDispatcher,
            json = Json,
            keychain = keychain,
            settingsStore = settingsStore,
            coreService = coreService,
            samRockHttpClient = samRockHttpClient,
        )
    }

    @Test
    fun `selected address type maps to descriptor account type`() {
        assertEquals(AccountType.LEGACY, "legacy".toSamRockAccountType())
        assertEquals(AccountType.WRAPPED_SEGWIT, "nestedSegwit".toSamRockAccountType())
        assertEquals(AccountType.NATIVE_SEGWIT, "nativeSegwit".toSamRockAccountType())
        assertEquals(AccountType.TAPROOT, "taproot".toSamRockAccountType())
        assertEquals(AccountType.NATIVE_SEGWIT, null.toSamRockAccountType())
        assertEquals(AccountType.NATIVE_SEGWIT, "unknown".toSamRockAccountType())
    }

    @Test
    fun `registerBitcoinOnchain derives descriptor and posts form payload`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(MNEMONIC)
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn("")
        whenever {
            onchainService.deriveOnchainDescriptor(
                mnemonicPhrase = MNEMONIC,
                network = Env.network,
                bip39Passphrase = null,
                accountType = AccountType.TAPROOT,
                accountIndex = 0u,
            )
        }.thenReturn(DESCRIPTOR)
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.OK, SUCCESS_RESPONSE))

        val result = sut.registerBitcoinOnchain(setupRequest())

        assertTrue(result.isSuccess)
        val payloadCaptor = argumentCaptor<String>()
        verify(samRockHttpClient).postDescriptorSetup(
            eq(setupRequest().postUrl),
            payloadCaptor.capture(),
        )
        assertEquals(
            """{"Version":"1.0","BTC":{"Descriptor":"$DESCRIPTOR"}}""",
            payloadCaptor.firstValue,
        )
    }

    @Test
    fun `registerBitcoinOnchain forwards non-empty passphrase`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(MNEMONIC)
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn(PASSPHRASE)
        whenever {
            onchainService.deriveOnchainDescriptor(
                mnemonicPhrase = MNEMONIC,
                network = Env.network,
                bip39Passphrase = PASSPHRASE,
                accountType = AccountType.TAPROOT,
                accountIndex = 0u,
            )
        }.thenReturn(DESCRIPTOR)
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.OK, SUCCESS_RESPONSE))

        val result = sut.registerBitcoinOnchain(setupRequest())

        assertTrue(result.isSuccess)
        verify(onchainService).deriveOnchainDescriptor(
            mnemonicPhrase = MNEMONIC,
            network = Env.network,
            bip39Passphrase = PASSPHRASE,
            accountType = AccountType.TAPROOT,
            accountIndex = 0u,
        )
    }

    @Test
    fun `registerBitcoinOnchain rejects unsupported setup before deriving descriptor`() = test {
        val error = assertNotNull(sut.registerBitcoinOnchain(unsupportedSetupRequest()).exceptionOrNull())

        assertEquals(UNSUPPORTED_SETUP, error.message)
        verify(onchainService, never()).deriveOnchainDescriptor(
            mnemonicPhrase = any(),
            network = any(),
            bip39Passphrase = any(),
            accountType = any(),
            accountIndex = any(),
        )
        verify(samRockHttpClient, never()).postDescriptorSetup(any(), any())
    }

    @Test
    fun `registerBitcoinOnchain fails without mnemonic before posting`() = test {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(null)

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(MISSING_MNEMONIC, error.message)
        verify(samRockHttpClient, never()).postDescriptorSetup(any(), any())
    }

    @Test
    fun `registerBitcoinOnchain uses non success response message`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.BadRequest, """{"Success":false,"Message":"bad otp"}"""))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals("bad otp", error.message)
    }

    @Test
    fun `registerBitcoinOnchain rejects missing top-level success`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.OK, """{"Result":{"BTC":{"Success":true}}}"""))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(INVALID_RESPONSE, error.message)
    }

    @Test
    fun `registerBitcoinOnchain requires BTC result`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.OK, """{"Success":true,"Result":{"Results":{}}}"""))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(MISSING_RESULT, error.message)
    }

    @Test
    fun `registerBitcoinOnchain propagates rejected BTC result message`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(
                SamRockHttpResponse(
                    HttpStatusCode.OK,
                    """{"Success":true,"Result":{"Results":{"BTC":{"Success":false,"Message":"rejected"}}}}"""
                )
            )

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals("rejected", error.message)
    }

    @Test
    fun `registerBitcoinOnchain accepts lowercase envelope and direct result map`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(
                SamRockHttpResponse(
                    HttpStatusCode.OK,
                    """{"success":true,"result":{"BTC":{"success":true}}}"""
                )
            )

        val result = sut.registerBitcoinOnchain(setupRequest())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `registerBitcoinOnchain uses lowercase non success response message`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.BadRequest, """{"success":false,"message":"bad otp"}"""))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals("bad otp", error.message)
    }

    @Test
    fun `registerBitcoinOnchain uses setup failed fallback`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.OK, """{"Success":false}"""))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(SETUP_FAILED, error.message)
    }

    @Test
    fun `registerBitcoinOnchain uses rejected descriptor fallback`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(
                SamRockHttpResponse(
                    HttpStatusCode.OK,
                    """{"Success":true,"Result":{"BTC":{"Success":false}}}"""
                )
            )

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(REJECTED_DESCRIPTOR, error.message)
    }

    @Test
    fun `registerBitcoinOnchain rejects nested non boolean BTC success`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(
                SamRockHttpResponse(
                    HttpStatusCode.OK,
                    """{"Success":true,"Result":{"BTC":{"Success":"true"}}}"""
                )
            )

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(MISSING_RESULT, error.message)
    }

    @Test
    fun `registerBitcoinOnchain rejects malformed response bodies`() = test {
        val cases = listOf(
            "<html></html>" to INVALID_RESPONSE,
            """{"Success":true}""" to MISSING_RESULT,
            """{"Success":true,"Result":"bad"}""" to MISSING_RESULT,
            """{"Success":true,"Result":{"Results":{"BTC":{}}}}""" to MISSING_RESULT,
            """{"Success":"true","Result":{"Results":{"BTC":{"Success":true}}}}""" to INVALID_RESPONSE,
            """{"Success":{},"Result":{"Results":{"BTC":{"Success":true}}}}""" to INVALID_RESPONSE,
            """{"Success":true,"Result":{"Results":{"BTC":{"Success":[]}}}}""" to MISSING_RESULT,
            """{"Success":true,"Result":{"Results":{"btc":{"Success":true}}}}""" to MISSING_RESULT,
        )

        cases.forEach { (body, expectedMessage) ->
            stubDescriptor()
            whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
                .thenReturn(SamRockHttpResponse(HttpStatusCode.OK, body))

            val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull(), body)

            assertEquals(expectedMessage, error.message, body)
        }
    }

    @Test
    fun `registerBitcoinOnchain uses status description for non success malformed body`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenReturn(SamRockHttpResponse(HttpStatusCode.InternalServerError, "<html></html>"))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(HttpStatusCode.InternalServerError.description, error.message)
    }

    @Test
    fun `registerBitcoinOnchain wraps transport errors without leaking URL`() = test {
        stubDescriptor()
        whenever(samRockHttpClient.postDescriptorSetup(any(), any()))
            .thenThrow(IllegalStateException("Failed request to ${setupRequest().postUrl}"))

        val error = assertNotNull(sut.registerBitcoinOnchain(setupRequest()).exceptionOrNull())

        assertEquals(REQUEST_ERROR, error.message)
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun `samRockFormBody uses json form field`() {
        val body = samRockFormBody("""{"BTC":{"Descriptor":"descriptor"}}""")

        assertEquals("""{"BTC":{"Descriptor":"descriptor"}}""", body.formData["json"])
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", body.contentType.toString())
    }

    @Test
    fun `response parser accepts uppercase envelope`() {
        val envelope = assertNotNull(
            SamRockResponseParser.decode(
                """
                {
                  "Success": true,
                  "Message": "ok",
                  "Result": {
                    "Results": {
                      "BTC": { "Success": true, "Message": "registered" }
                    }
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(envelope.success == true)
        assertEquals("ok", envelope.message)
        assertTrue(envelope.result?.results?.get("BTC")?.success == true)
        assertEquals("registered", envelope.result?.results?.get("BTC")?.message)
    }

    @Test
    fun `response parser accepts lowercase envelope and direct result map`() {
        val envelope = assertNotNull(
            SamRockResponseParser.decode(
                """
                {
                  "success": true,
                  "result": {
                    "BTC": { "success": true }
                  }
                }
                """.trimIndent()
            )
        )

        assertTrue(envelope.success == true)
        assertTrue(envelope.result?.results?.get("BTC")?.success == true)
    }

    @Test
    fun `response parser preserves failed method message`() {
        val envelope = assertNotNull(
            SamRockResponseParser.decode(
                """
                {
                  "success": true,
                  "result": {
                    "results": {
                      "BTC": { "success": false, "message": "descriptor rejected" }
                    }
                  }
                }
                """.trimIndent()
            )
        )
        val btcResult = assertNotNull(envelope.result?.results?.get("BTC"))

        assertFalse(btcResult.success)
        assertEquals("descriptor rejected", btcResult.message)
    }

    @Test
    fun `response parser rejects invalid or non-object json`() {
        assertNull(SamRockResponseParser.decode("<html></html>"))
        assertNull(SamRockResponseParser.decode("[]"))
    }

    private suspend fun stubDescriptor() {
        whenever(keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)).thenReturn(MNEMONIC)
        whenever(keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)).thenReturn(null)
        whenever {
            onchainService.deriveOnchainDescriptor(
                mnemonicPhrase = MNEMONIC,
                network = Env.network,
                bip39Passphrase = null,
                accountType = AccountType.TAPROOT,
                accountIndex = 0u,
            )
        }.thenReturn(DESCRIPTOR)
    }

    private fun setupRequest() = SamRockSetupRequest(
        postUrl = POST_URL,
        storeId = "store",
        otp = "secret",
        requestedMethods = setOf(SamRockPaymentMethod.BTC_ONCHAIN),
        hasUnknownMethods = false,
        hostDisplayName = "btcpay.example.com",
        logDescription = "https://btcpay.example.com/plugins/store/samrock/protocol",
    )

    private fun unsupportedSetupRequest() = SamRockSetupRequest(
        postUrl = POST_URL,
        storeId = "store",
        otp = "secret",
        requestedMethods = setOf(SamRockPaymentMethod.BTC_LIGHTNING),
        hasUnknownMethods = false,
        hostDisplayName = "btcpay.example.com",
        logDescription = "https://btcpay.example.com/plugins/store/samrock/protocol",
    )
}
