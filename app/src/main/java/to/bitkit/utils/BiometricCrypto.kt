package to.bitkit.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class BiometricCrypto {
    private val keystoreType = "AndroidKeyStore"
    private val keyAlias = "bitkit_bio_key"
    private val keyStore = KeyStore.getInstance(keystoreType).apply { load(null) }

    private val algorithm = KeyProperties.KEY_ALGORITHM_AES
    private val blockMode = KeyProperties.BLOCK_MODE_GCM
    private val padding = KeyProperties.ENCRYPTION_PADDING_NONE
    private val transformation = "$algorithm/$blockMode/$padding"

    fun getCryptoObject(): BiometricPrompt.CryptoObject {
        val cipher = Cipher.getInstance(transformation)
        val key = getOrCreateKey()

        cipher.init(Cipher.ENCRYPT_MODE, key)

        return BiometricPrompt.CryptoObject(cipher)
    }

    fun validateCipher(cryptoObject: BiometricPrompt.CryptoObject?): Boolean {
        return try {
            val cipher = cryptoObject?.cipher ?: return false

            // Try to update with empty data to verify cipher is properly initialized
            cipher.updateAAD(ByteArray(0))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(algorithm, keystoreType)

        val keyGenSpec = KeyGenParameterSpec
            .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(blockMode)
            .setEncryptionPaddings(padding)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }
}
