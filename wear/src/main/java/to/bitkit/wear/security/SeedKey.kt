package to.bitkit.wear.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import android.security.KeyStoreException as AndroidKeyStoreException

/**
 * The key that wraps the seed: the seam between [KeystoreSeedStore] and the platform Keystore, which unit tests
 * cannot run. Every method fails with a [SeedStoreError].
 */
interface SeedKey {
    /**
     * Creates the key, replacing any earlier one, and returns where it lives. With [isUnlockedDeviceRequired] the key
     * only works while the watch is unlocked, which needs a screen lock to be set.
     */
    fun create(isUnlockedDeviceRequired: Boolean): KeyBacking

    /** Returns the IV followed by the ciphertext and tag of [plaintext], bound to [aad]. */
    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Reverses [encrypt]. */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray

    fun delete()
}

enum class KeyBacking { StrongBox, TrustedEnvironment, Software, Unknown }

/**
 * An AES-256-GCM key in AndroidKeyStore that never leaves secure hardware: StrongBox when the watch has one and it
 * can make the key, else the trusted execution environment. It is not bound to user authentication. `docs/wear.md`
 * explains the choices.
 */
@Singleton
class AndroidKeystoreSeedKey @Inject constructor(
    @ApplicationContext private val context: Context,
) : SeedKey {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "wear_seed_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /**
         * Maps what the platform Keystore throws to the store's errors. Only certain key loss is reported as
         * [SeedStoreError.KeyInvalidated], because that verdict deletes the wallet: a permanently invalidated key, or
         * an unrecoverable key whose cause is a permanent "key does not exist". A lock wins over both, and every
         * other error, transient ones included, is the retryable [SeedStoreError.Failed]. `docs/wear.md` lists the
         * platform behaviour this follows.
         */
        internal fun mapError(error: Throwable): SeedStoreError {
            if (error is SeedStoreError) return error
            val chain = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            return when {
                chain.any { it is UserNotAuthenticatedException || it.isLockedError() } ->
                    SeedStoreError.DeviceLocked(error)
                chain.any { it.isKeyLoss() } -> SeedStoreError.KeyInvalidated(error)
                chain.any { it is AEADBadTagException } -> SeedStoreError.Corrupted(error)
                else -> SeedStoreError.Failed(error)
            }
        }

        private const val MAX_CAUSE_DEPTH = 8

        private fun Throwable.isLockedError(): Boolean = this is AndroidKeyStoreException &&
            numericErrorCode == AndroidKeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED

        private fun Throwable.isKeyLoss(): Boolean = when (this) {
            is KeyPermanentlyInvalidatedException -> true
            is UnrecoverableKeyException -> (cause as? AndroidKeyStoreException)?.isMissingKey() == true
            else -> false
        }

        private fun AndroidKeyStoreException.isMissingKey(): Boolean =
            numericErrorCode == AndroidKeyStoreException.ERROR_KEY_DOES_NOT_EXIST && !isTransientFailure
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    override fun create(isUnlockedDeviceRequired: Boolean): KeyBacking = guarded {
        keyStore.deleteEntry(ALIAS)
        val hasStrongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        val key = if (hasStrongBox) {
            runCatching { generate(isStrongBox = true, isUnlockedDeviceRequired) }.getOrElse {
                keyStore.deleteEntry(ALIAS)
                generate(isStrongBox = false, isUnlockedDeviceRequired)
            }
        } else {
            generate(isStrongBox = false, isUnlockedDeviceRequired)
        }
        backing(key)
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, requireKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        check(cipher.iv.size == IV_BYTES) { "Unexpected IV size" }
        cipher.iv + ciphertext
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = guarded {
        if (blob.size <= IV_BYTES) throw SeedStoreError.Corrupted()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    override fun delete() = guarded { keyStore.deleteEntry(ALIAS) }

    private fun requireKey(): SecretKey =
        keyStore.getKey(ALIAS, null) as? SecretKey ?: throw SeedStoreError.KeyInvalidated()

    private fun generate(isStrongBox: Boolean, isUnlockedDeviceRequired: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(KEY_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(isUnlockedDeviceRequired)
            .setIsStrongBoxBacked(isStrongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun backing(key: SecretKey): KeyBacking {
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.StrongBox
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.TrustedEnvironment
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyBacking.Software
            else -> KeyBacking.Unknown
        }
    }

    private inline fun <T> guarded(block: () -> T): T = runCatching(block).getOrElse { throw mapError(it) }
}
