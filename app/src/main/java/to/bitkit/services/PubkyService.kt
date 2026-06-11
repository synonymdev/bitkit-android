package to.bitkit.services

import android.content.Context
import com.synonym.bitkitcore.PubkyProfile
import com.synonym.bitkitcore.approvePubkyAuth
import com.synonym.bitkitcore.cancelPubkyAuth
import com.synonym.bitkitcore.completePubkyAuth
import com.synonym.bitkitcore.derivePubkySecretKey
import com.synonym.bitkitcore.fetchPubkyContacts
import com.synonym.bitkitcore.fetchPubkyFile
import com.synonym.bitkitcore.fetchPubkyProfile
import com.synonym.bitkitcore.mnemonicToSeed
import com.synonym.bitkitcore.parsePubkyAuthUrl
import com.synonym.bitkitcore.pubkyPublicKeyFromSecret
import com.synonym.bitkitcore.pubkyPutWithSecretKey
import com.synonym.bitkitcore.pubkySessionDelete
import com.synonym.bitkitcore.pubkySessionList
import com.synonym.bitkitcore.pubkySessionPut
import com.synonym.bitkitcore.pubkySignIn
import com.synonym.bitkitcore.pubkySignUp
import com.synonym.bitkitcore.startPubkyAuth
import com.synonym.paykit.FfiHandshakeProgress
import com.synonym.paykit.FfiPaymentEndpoint
import com.synonym.paykit.FfiPrivatePaymentList
import com.synonym.paykit.PaykitAndroid
import com.synonym.paykit.paykitAcceptEncryptedLink
import com.synonym.paykit.paykitAdvanceHandshake
import com.synonym.paykit.paykitCloseEncryptedLink
import com.synonym.paykit.paykitDropEncryptedLinkHandshake
import com.synonym.paykit.paykitEncryptedLinkHandshakeSnapshotRecipient
import com.synonym.paykit.paykitEncryptedLinkSnapshotRecipient
import com.synonym.paykit.paykitExportSession
import com.synonym.paykit.paykitForceSignOut
import com.synonym.paykit.paykitGetCurrentPublicKey
import com.synonym.paykit.paykitGetPaymentEndpoint
import com.synonym.paykit.paykitGetPaymentList
import com.synonym.paykit.paykitImportSession
import com.synonym.paykit.paykitInitialize
import com.synonym.paykit.paykitInitiateEncryptedLink
import com.synonym.paykit.paykitIsAuthenticated
import com.synonym.paykit.paykitParsePrivatePaymentListJson
import com.synonym.paykit.paykitReceivePrivateApplicationMessages
import com.synonym.paykit.paykitRemovePaymentEndpoint
import com.synonym.paykit.paykitRestoreEncryptedLink
import com.synonym.paykit.paykitRestoreEncryptedLinkHandshake
import com.synonym.paykit.paykitSerializeEncryptedLink
import com.synonym.paykit.paykitSerializeEncryptedLinkHandshake
import com.synonym.paykit.paykitSetPaymentEndpoint
import com.synonym.paykit.paykitSetPrivatePaymentList
import com.synonym.paykit.paykitSignOut
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import to.bitkit.async.ServiceQueue
import to.bitkit.env.Env
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("TooManyFunctions")
@Singleton
class PubkyService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val isSetup = CompletableDeferred<Unit>()

    suspend fun initialize() = ServiceQueue.CORE.background {
        if (!PaykitAndroid.initialize(context)) {
            throw AppError("Failed to initialize Android platform verifier")
        }
        paykitInitialize()
        isSetup.complete(Unit)
    }

    // region Session management

    suspend fun importSession(secret: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        paykitImportSession(secret)
    }

    suspend fun exportSession(): String = ServiceQueue.CORE.background {
        isSetup.await()
        paykitExportSession()
    }

    suspend fun isAuthenticated(): Boolean = ServiceQueue.CORE.background {
        isSetup.await()
        paykitIsAuthenticated()
    }

    suspend fun currentPublicKey(): String? = ServiceQueue.CORE.background {
        isSetup.await()
        paykitGetCurrentPublicKey()
    }

    suspend fun signOut() = ServiceQueue.CORE.background {
        isSetup.await()
        paykitSignOut()
    }

    suspend fun forceSignOut() = ServiceQueue.CORE.background {
        isSetup.await()
        paykitForceSignOut()
    }

    // endregion

    // region Payment endpoints

    suspend fun getPaymentList(publicKey: String): List<FfiPaymentEndpoint> = ServiceQueue.CORE.background {
        isSetup.await()
        paykitGetPaymentList(publicKey)
    }

    suspend fun getPaymentEndpoint(publicKey: String, methodId: String): String? = ServiceQueue.CORE.background {
        isSetup.await()
        paykitGetPaymentEndpoint(publicKey, methodId)
    }

    suspend fun setPaymentEndpoint(methodId: String, endpointData: String) = ServiceQueue.CORE.background {
        isSetup.await()
        paykitSetPaymentEndpoint(methodId, endpointData)
    }

    suspend fun removePaymentEndpoint(methodId: String) = ServiceQueue.CORE.background {
        isSetup.await()
        paykitRemovePaymentEndpoint(methodId)
    }

    // endregion

    // region Private payment endpoints

    suspend fun initiateEncryptedLink(secretKeyHex: String, receiverPublicKey: String): String =
        ServiceQueue.CORE.background {
            isSetup.await()
            paykitInitiateEncryptedLink(secretKeyHex, receiverPublicKey)
        }

    suspend fun acceptEncryptedLink(secretKeyHex: String, senderPublicKey: String): String =
        ServiceQueue.CORE.background {
            isSetup.await()
            paykitAcceptEncryptedLink(secretKeyHex, senderPublicKey)
        }

    suspend fun advanceHandshake(handshakeId: String): FfiHandshakeProgress = ServiceQueue.CORE.background {
        isSetup.await()
        paykitAdvanceHandshake(handshakeId)
    }

    suspend fun restoreEncryptedLink(secretKeyHex: String, snapshotHex: String): String =
        ServiceQueue.CORE.background {
            isSetup.await()
            paykitRestoreEncryptedLink(secretKeyHex, snapshotHex)
        }

    suspend fun encryptedLinkSnapshotRecipient(snapshotHex: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        paykitEncryptedLinkSnapshotRecipient(snapshotHex)
    }

    suspend fun restoreEncryptedLinkHandshake(secretKeyHex: String, snapshotHex: String): String =
        ServiceQueue.CORE.background {
            isSetup.await()
            paykitRestoreEncryptedLinkHandshake(secretKeyHex, snapshotHex)
        }

    suspend fun encryptedLinkHandshakeSnapshotRecipient(snapshotHex: String): String =
        ServiceQueue.CORE.background {
            isSetup.await()
            paykitEncryptedLinkHandshakeSnapshotRecipient(snapshotHex)
        }

    suspend fun serializeEncryptedLink(linkId: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        paykitSerializeEncryptedLink(linkId)
    }

    suspend fun serializeEncryptedLinkHandshake(handshakeId: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        paykitSerializeEncryptedLinkHandshake(handshakeId)
    }

    suspend fun closeEncryptedLink(linkId: String) = ServiceQueue.CORE.background {
        isSetup.await()
        paykitCloseEncryptedLink(linkId)
    }

    suspend fun dropEncryptedLinkHandshake(handshakeId: String) = ServiceQueue.CORE.background {
        isSetup.await()
        paykitDropEncryptedLinkHandshake(handshakeId)
    }

    suspend fun setPrivatePayments(linkId: String, entries: List<FfiPaymentEndpoint>) =
        ServiceQueue.CORE.background {
            isSetup.await()
            paykitSetPrivatePaymentList(linkId, FfiPrivatePaymentList(paymentEndpoints = entries))
        }

    suspend fun getPrivatePayments(linkId: String): FfiPrivatePaymentList? = ServiceQueue.CORE.background {
        isSetup.await()
        paykitReceivePrivateApplicationMessages(linkId)
            .asReversed()
            .firstNotNullOfOrNull { runCatching { paykitParsePrivatePaymentListJson(it.rawJson) }.getOrNull() }
    }

    // endregion

    // region Key derivation

    suspend fun mnemonicToSeed(mnemonic: String, passphrase: String?): ByteArray =
        ServiceQueue.CORE.background {
            isSetup.await()
            mnemonicToSeed(mnemonicPhrase = mnemonic, passphrase = passphrase ?: "")
        }

    suspend fun deriveSecretKey(seed: ByteArray): String = ServiceQueue.CORE.background {
        isSetup.await()
        derivePubkySecretKey(seed)
    }

    suspend fun publicKeyFromSecret(secretKeyHex: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        pubkyPublicKeyFromSecret(secretKeyHex)
    }

    // endregion

    // region Homeserver auth

    suspend fun signUp(secretKeyHex: String, homeserverZ32: String, signupCode: String?): String =
        ServiceQueue.CORE.background {
            isSetup.await()
            pubkySignUp(secretKeyHex, homeserverZ32, signupCode)
        }

    suspend fun signIn(secretKeyHex: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        pubkySignIn(secretKeyHex)
    }

    // endregion

    // region Auth flow (Ring)

    suspend fun startAuth(): String = ServiceQueue.CORE.background {
        isSetup.await()
        startPubkyAuth(Env.pubkyCapabilities)
    }

    suspend fun completeAuth(): String = ServiceQueue.CORE.background {
        isSetup.await()
        completePubkyAuth()
    }

    suspend fun cancelAuth() = ServiceQueue.CORE.background {
        isSetup.await()
        cancelPubkyAuth()
    }

    // endregion

    // region Auth approval

    suspend fun parseAuthUrl(url: String) = ServiceQueue.CORE.background {
        isSetup.await()
        parsePubkyAuthUrl(url)
    }

    suspend fun approveAuth(authUrl: String, secretKeyHex: String) = ServiceQueue.CORE.background {
        isSetup.await()
        approvePubkyAuth(authUrl, secretKeyHex)
    }

    // endregion

    // region File operations

    suspend fun fetchFile(uri: String): ByteArray = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyFile(uri)
    }

    suspend fun fetchFileString(uri: String): String = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyFile(uri).toString(Charsets.UTF_8)
    }

    suspend fun sessionPut(sessionSecret: String, path: String, content: ByteArray) =
        ServiceQueue.CORE.background {
            isSetup.await()
            pubkySessionPut(sessionSecret, path, content)
        }

    suspend fun sessionDelete(sessionSecret: String, path: String) =
        ServiceQueue.CORE.background {
            isSetup.await()
            pubkySessionDelete(sessionSecret, path)
        }

    suspend fun sessionList(sessionSecret: String, dirPath: String): List<String> =
        ServiceQueue.CORE.background {
            isSetup.await()
            pubkySessionList(sessionSecret, dirPath)
        }

    suspend fun putWithSecretKey(secretKeyHex: String, path: String, content: ByteArray) =
        ServiceQueue.CORE.background {
            isSetup.await()
            pubkyPutWithSecretKey(secretKeyHex, path, content)
        }

    // endregion

    // region Profile & contacts

    suspend fun getProfile(publicKey: String): PubkyProfile = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyProfile(publicKey)
    }

    suspend fun getContacts(publicKey: String): List<String> = ServiceQueue.CORE.background {
        isSetup.await()
        fetchPubkyContacts(publicKey)
    }

    // endregion
}
