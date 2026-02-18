package to.bitkit.services

import com.synonym.bitkitcore.TrezorAddressResponse
import com.synonym.bitkitcore.TrezorDeviceInfo
import com.synonym.bitkitcore.TrezorFeatures
import com.synonym.bitkitcore.TrezorGetAddressParams
import com.synonym.bitkitcore.TrezorGetPublicKeyParams
import com.synonym.bitkitcore.TrezorPublicKeyResponse
import com.synonym.bitkitcore.TrezorScriptType
import com.synonym.bitkitcore.TrezorSignMessageParams
import com.synonym.bitkitcore.TrezorSignTxParams
import com.synonym.bitkitcore.TrezorSignedMessageResponse
import com.synonym.bitkitcore.TrezorSignedTx
import com.synonym.bitkitcore.TrezorTxInput
import com.synonym.bitkitcore.TrezorTxOutput
import com.synonym.bitkitcore.TrezorVerifyMessageParams
import com.synonym.bitkitcore.trezorClearCredentials
import com.synonym.bitkitcore.trezorConnect
import com.synonym.bitkitcore.trezorDisconnect
import com.synonym.bitkitcore.trezorGetAddress
import com.synonym.bitkitcore.trezorGetConnectedDevice
import com.synonym.bitkitcore.trezorGetPublicKey
import com.synonym.bitkitcore.trezorInitialize
import com.synonym.bitkitcore.trezorIsConnected
import com.synonym.bitkitcore.trezorIsInitialized
import com.synonym.bitkitcore.trezorListDevices
import com.synonym.bitkitcore.trezorScan
import com.synonym.bitkitcore.trezorSetTransportCallback
import com.synonym.bitkitcore.trezorSignMessage
import com.synonym.bitkitcore.trezorSignTx
import com.synonym.bitkitcore.trezorVerifyMessage
import to.bitkit.async.ServiceQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrezorService @Inject constructor(
    private val transport: TrezorTransport,
) {
    @Volatile
    private var callbackRegistered = false

    private fun ensureCallbackRegistered() {
        if (!callbackRegistered) {
            synchronized(this) {
                if (!callbackRegistered) {
                    trezorSetTransportCallback(transport)
                    callbackRegistered = true
                }
            }
        }
    }

    suspend fun initialize(credentialPath: String? = null) {
        ServiceQueue.CORE.background {
            ensureCallbackRegistered()
            trezorInitialize(credentialPath = credentialPath)
        }
    }

    suspend fun isInitialized(): Boolean {
        return ServiceQueue.CORE.background {
            trezorIsInitialized()
        }
    }

    suspend fun scan(): List<TrezorDeviceInfo> {
        return ServiceQueue.CORE.background {
            trezorScan()
        }
    }

    suspend fun listDevices(): List<TrezorDeviceInfo> {
        return ServiceQueue.CORE.background {
            trezorListDevices()
        }
    }

    suspend fun connect(deviceId: String): TrezorFeatures {
        return ServiceQueue.CORE.background {
            trezorConnect(deviceId = deviceId)
        }
    }

    suspend fun isConnected(): Boolean {
        return ServiceQueue.CORE.background {
            trezorIsConnected()
        }
    }

    suspend fun getAddress(
        path: String,
        coin: String? = "Bitcoin",
        showOnTrezor: Boolean = false,
        scriptType: TrezorScriptType? = null,
    ): TrezorAddressResponse {
        return ServiceQueue.CORE.background {
            trezorGetAddress(
                params = TrezorGetAddressParams(
                    path = path,
                    coin = coin,
                    showOnTrezor = showOnTrezor,
                    scriptType = scriptType,
                )
            )
        }
    }

    suspend fun getPublicKey(
        path: String,
        coin: String? = "Bitcoin",
        showOnTrezor: Boolean = false,
    ): TrezorPublicKeyResponse {
        return ServiceQueue.CORE.background {
            trezorGetPublicKey(
                params = TrezorGetPublicKeyParams(
                    path = path,
                    coin = coin,
                    showOnTrezor = showOnTrezor,
                )
            )
        }
    }

    suspend fun disconnect() {
        ServiceQueue.CORE.background {
            trezorDisconnect()
        }
    }

    suspend fun getConnectedDevice(): TrezorDeviceInfo? {
        return ServiceQueue.CORE.background {
            trezorGetConnectedDevice()
        }
    }

    suspend fun signMessage(
        path: String,
        message: String,
        coin: String? = "Bitcoin",
    ): TrezorSignedMessageResponse {
        return ServiceQueue.CORE.background {
            trezorSignMessage(
                params = TrezorSignMessageParams(
                    path = path,
                    message = message,
                    coin = coin,
                )
            )
        }
    }

    suspend fun verifyMessage(
        address: String,
        signature: String,
        message: String,
        coin: String? = "Bitcoin",
    ): Boolean {
        return ServiceQueue.CORE.background {
            trezorVerifyMessage(
                params = TrezorVerifyMessageParams(
                    address = address,
                    signature = signature,
                    message = message,
                    coin = coin,
                )
            )
        }
    }

    suspend fun signTx(
        inputs: List<TrezorTxInput>,
        outputs: List<TrezorTxOutput>,
        coin: String? = "Bitcoin",
        lockTime: UInt? = null,
        version: UInt? = null,
    ): TrezorSignedTx {
        return ServiceQueue.CORE.background {
            trezorSignTx(
                params = TrezorSignTxParams(
                    inputs = inputs,
                    outputs = outputs,
                    coin = coin,
                    lockTime = lockTime,
                    version = version,
                )
            )
        }
    }

    suspend fun clearCredentials(deviceId: String) {
        ServiceQueue.CORE.background {
            trezorClearCredentials(deviceId = deviceId)
        }
    }
}
