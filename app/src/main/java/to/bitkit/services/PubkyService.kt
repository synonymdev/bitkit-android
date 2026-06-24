package to.bitkit.services

import com.synonym.bitkitcore.mnemonicToSeed
import com.synonym.paykit.ContactProfileResolution
import com.synonym.paykit.ContactRecord
import com.synonym.paykit.PaykitProfile
import to.bitkit.async.ServiceQueue
import to.bitkit.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("TooManyFunctions")
@Singleton
class PubkyService @Inject constructor(
    private val paykitSdkService: PaykitSdkService,
) {
    suspend fun initialize() = ServiceQueue.CORE.background {
        paykitSdkService.initialize()
    }

    // region Session management

    suspend fun importSession(secret: String): String = ServiceQueue.CORE.background {
        paykitSdkService.importSession(secret).publicKey
    }

    suspend fun importExternalSession(secret: String): String = ServiceQueue.CORE.background {
        paykitSdkService.importSession(secret, includeLocalSecret = false).publicKey
    }

    suspend fun currentPublicKey(): String? = ServiceQueue.CORE.background {
        paykitSdkService.currentPublicKey()
    }

    suspend fun signOut() = ServiceQueue.CORE.background {
        paykitSdkService.signOut()
    }

    suspend fun forceSignOut() = ServiceQueue.CORE.background {
        paykitSdkService.forceSignOut()
    }

    suspend fun clearSessionAccess() = ServiceQueue.CORE.background {
        paykitSdkService.clearSessionAccess()
    }

    suspend fun removeBitkitPaymentEndpoints() = ServiceQueue.CORE.background {
        val report = paykitSdkService.syncPublicEndpoints(emptyList())
        if (report.failed.isNotEmpty()) throw AppError("Failed to remove Paykit payment endpoints")
    }

    // endregion

    // region Key derivation

    suspend fun mnemonicToSeed(mnemonic: String, passphrase: String?): ByteArray =
        ServiceQueue.CORE.background {
            mnemonicToSeed(mnemonicPhrase = mnemonic, passphrase = passphrase ?: "")
        }

    suspend fun deriveSecretKey(seed: ByteArray): String = ServiceQueue.CORE.background {
        PaykitSdkService.deriveSecretKey(seed)
    }

    suspend fun publicKeyFromSecret(secretKeyHex: String): String = ServiceQueue.CORE.background {
        PaykitSdkService.publicKeyFromSecret(secretKeyHex)
    }

    // endregion

    // region Homeserver auth

    suspend fun signUp(secretKeyHex: String, homeserverZ32: String, signupCode: String?): Unit =
        ServiceQueue.CORE.background {
            paykitSdkService.signUp(secretKeyHex, homeserverZ32, signupCode)
            Unit
        }

    suspend fun signIn(secretKeyHex: String): Unit = ServiceQueue.CORE.background {
        paykitSdkService.signIn(secretKeyHex)
        Unit
    }

    // endregion

    // region Auth flow (Ring)

    suspend fun startAuth(): String = ServiceQueue.CORE.background {
        paykitSdkService.startAuth()
    }

    suspend fun completeAuth(): Unit = ServiceQueue.CORE.background {
        paykitSdkService.completeAuth()
        Unit
    }

    suspend fun cancelAuth() = ServiceQueue.CORE.background {
        paykitSdkService.cancelAuth()
    }

    // endregion

    // region Auth approval

    suspend fun parseAuthUrl(url: String) = ServiceQueue.CORE.background {
        PaykitSdkService.parseAuthUrl(url)
    }

    suspend fun approveAuth(authUrl: String, secretKeyHex: String) = ServiceQueue.CORE.background {
        paykitSdkService.approveAuth(authUrl, secretKeyHex)
    }

    // endregion

    // region File operations

    suspend fun fetchFile(uri: String): ByteArray = ServiceQueue.CORE.background {
        paykitSdkService.fetchFile(uri)
    }

    // endregion

    // region Profile & contacts

    suspend fun publishPaykitProfile(profile: PaykitProfile) = ServiceQueue.CORE.background {
        paykitSdkService.publishPaykitProfile(profile)
    }

    suspend fun uploadProfileAvatar(bytes: ByteArray, contentType: String): String = ServiceQueue.CORE.background {
        paykitSdkService.uploadProfileAvatar(bytes, contentType)
    }

    suspend fun deletePaykitProfile() = ServiceQueue.CORE.background {
        paykitSdkService.deletePaykitProfile()
    }

    suspend fun getContacts(publicKey: String): List<String> = ServiceQueue.CORE.background {
        paykitSdkService.fetchPubkyFollows(publicKey)
    }

    suspend fun contactRecords(): List<ContactRecord> = ServiceQueue.CORE.background {
        paykitSdkService.contactRecords()
    }

    suspend fun saveContact(publicKey: String, label: String?): ContactRecord = ServiceQueue.CORE.background {
        paykitSdkService.saveContact(publicKey, label)
    }

    suspend fun removeContact(publicKey: String): ContactRecord? = ServiceQueue.CORE.background {
        paykitSdkService.removeContact(publicKey)
    }

    suspend fun resolveContactProfile(
        publicKey: String,
        allowPubkyProfileFallback: Boolean,
    ): ContactProfileResolution? = ServiceQueue.CORE.background {
        paykitSdkService.resolveContactProfile(publicKey, allowPubkyProfileFallback)
    }

    // endregion
}
