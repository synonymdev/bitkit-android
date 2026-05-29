package to.bitkit.repositories

import android.content.Context
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.AddressType
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.models.DEFAULT_ADDRESS_TYPE
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.models.toAddressType
import to.bitkit.services.CoreService
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("LongParameterList")
class SamRockRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val json: Json,
    private val keychain: Keychain,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val samRockHttpClient: SamRockHttpClient,
) {
    companion object {
        private const val BITCOIN_METHOD = "BTC"
    }

    suspend fun registerBitcoinOnchain(setup: SamRockSetupRequest): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            if (!setup.requestsBitcoinOnchain) {
                throw AppError(context.getString(R.string.btcpay__unsupported_text))
            }

            val descriptor = derivePrimaryAddressDescriptor()
            val payload = json.encodeToString(
                SamRockDescriptorPayload(
                    btc = SamRockBitcoinDescriptor(descriptor = descriptor),
                )
            )

            val response = runCatching { samRockHttpClient.postDescriptorSetup(setup.postUrl, payload) }
                .getOrElse {
                    throw SamRockTransportError(
                        message = context.getString(R.string.btcpay__request_error),
                        cause = it,
                    )
                }
            val envelope = SamRockResponseParser.decode(response.body)

            if (!response.status.isSuccess()) {
                throw AppError(envelope?.message ?: response.status.description)
            }

            if (envelope == null) {
                throw AppError(context.getString(R.string.btcpay__invalid_response))
            }

            when (envelope.success) {
                true -> Unit
                false -> throw AppError(envelope.message ?: context.getString(R.string.btcpay__setup_failed))
                null -> throw AppError(context.getString(R.string.btcpay__invalid_response))
            }

            val btcResult = envelope.result?.results?.get(BITCOIN_METHOD)
                ?: throw AppError(context.getString(R.string.btcpay__missing_result))

            if (!btcResult.success) {
                throw AppError(btcResult.message ?: context.getString(R.string.btcpay__rejected_descriptor))
            }
        }
    }

    private suspend fun derivePrimaryAddressDescriptor(): String {
        val mnemonic = keychain.loadString(Keychain.Key.BIP39_MNEMONIC.name)
            ?: throw AppError(context.getString(R.string.btcpay__missing_mnemonic))
        val passphrase = keychain.loadString(Keychain.Key.BIP39_PASSPHRASE.name)?.takeIf { it.isNotEmpty() }
        val selectedAddressType = settingsStore.data.first().selectedAddressType

        return coreService.onchain.deriveOnchainDescriptor(
            mnemonicPhrase = mnemonic,
            network = Env.network,
            bip39Passphrase = passphrase,
            accountType = selectedAddressType.toSamRockAccountType(),
            accountIndex = 0u,
        )
    }
}

private class SamRockTransportError(
    message: String,
    cause: Throwable?,
) : AppError(message, cause)

@Singleton
class SamRockHttpClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val CHARSET_PARAMETER = "charset"
        private const val UTF8_PARAMETER = "utf-8"
    }

    suspend fun postDescriptorSetup(
        postUrl: String,
        payload: String,
    ): SamRockHttpResponse {
        val response = httpClient.post(postUrl) {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.FormUrlEncoded.withParameter(CHARSET_PARAMETER, UTF8_PARAMETER))
            setBody(samRockFormBody(payload))
        }

        return SamRockHttpResponse(
            status = response.status,
            body = response.bodyAsText(),
        )
    }
}

data class SamRockHttpResponse(
    val status: HttpStatusCode,
    val body: String,
)

internal fun samRockFormBody(payload: String): FormDataContent {
    return FormDataContent(
        Parameters.build {
            append(JSON_FORM_FIELD, payload)
        }
    )
}

internal object SamRockResponseParser {
    private val parser = Json { ignoreUnknownKeys = true }

    fun decode(body: String): SamRockResponseEnvelope? {
        val root = runCatching { parser.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return SamRockResponseEnvelope(
            success = root.booleanFor(RESPONSE_SUCCESS_KEYS),
            message = root.stringFor(RESPONSE_MESSAGE_KEYS),
            result = root.objectFor(RESPONSE_RESULT_KEYS)?.toSetupResponse(),
        )
    }

    private fun JsonObject.toSetupResponse(): SamRockSetupResponse {
        val resultsObject = objectFor(RESPONSE_RESULTS_KEYS) ?: this
        return SamRockSetupResponse(
            results = resultsObject.mapNotNull { (key, value) ->
                val methodObject = value as? JsonObject ?: return@mapNotNull null
                val success = methodObject.booleanFor(RESPONSE_SUCCESS_KEYS) ?: return@mapNotNull null
                key to SamRockMethodResponse(
                    success = success,
                    message = methodObject.stringFor(RESPONSE_MESSAGE_KEYS),
                )
            }.toMap()
        )
    }

    private fun JsonObject.objectFor(names: List<String>): JsonObject? {
        return elementFor(names) as? JsonObject
    }

    private fun JsonObject.booleanFor(names: List<String>): Boolean? {
        val primitive = elementFor(names) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    private fun JsonObject.stringFor(names: List<String>): String? {
        return (elementFor(names) as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.elementFor(names: List<String>): JsonElement? {
        return names.firstNotNullOfOrNull { this[it] }
    }

    private val RESPONSE_SUCCESS_KEYS = listOf("Success", "success")
    private val RESPONSE_MESSAGE_KEYS = listOf("Message", "message")
    private val RESPONSE_RESULT_KEYS = listOf("Result", "result")
    private val RESPONSE_RESULTS_KEYS = listOf("Results", "results")
}

internal data class SamRockResponseEnvelope(
    val success: Boolean?,
    val message: String?,
    val result: SamRockSetupResponse?,
)

internal data class SamRockSetupResponse(
    val results: Map<String, SamRockMethodResponse>,
)

internal data class SamRockMethodResponse(
    val success: Boolean,
    val message: String?,
)

internal fun String?.toSamRockAccountType(): AccountType {
    return (this?.toAddressType() ?: DEFAULT_ADDRESS_TYPE).let {
        when (it) {
            AddressType.P2PKH -> AccountType.LEGACY
            AddressType.P2SH -> AccountType.WRAPPED_SEGWIT
            AddressType.P2TR -> AccountType.TAPROOT
            else -> AccountType.NATIVE_SEGWIT
        }
    }
}

@Serializable
private data class SamRockDescriptorPayload(
    @SerialName("BTC")
    val btc: SamRockBitcoinDescriptor,
)

@Serializable
private data class SamRockBitcoinDescriptor(
    @SerialName("Descriptor")
    val descriptor: String,
)

private const val JSON_FORM_FIELD = "json"
