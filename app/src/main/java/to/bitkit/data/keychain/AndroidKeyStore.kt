package to.bitkit.data.keychain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeyStore(
    private val alias: String,
    private val password: CharArray? = null,
) {
    private val type = "AndroidKeyStore"

    private val algorithm = KeyProperties.KEY_ALGORITHM_AES
    private val blockMode = KeyProperties.BLOCK_MODE_GCM
    private val padding = KeyProperties.ENCRYPTION_PADDING_NONE
    private val transformation = "$algorithm/$blockMode/$padding"

    private val ivLength = 12 // GCM typically uses a 12-byte IV

    private val keyStore by lazy { KeyStore.getInstance(type).apply { load(null) } }

    init {
        generateKey()
    }

    private fun generateKey() {
        if (!keyStore.containsAlias(alias)) {
            try {
                val generator = KeyGenerator.getInstance(algorithm, type)
                generator.init(buildSpec(isStrongboxBacked = true))
                generator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                val generator = KeyGenerator.getInstance(algorithm, type)
                generator.init(buildSpec(isStrongboxBacked = false))
                generator.generateKey()
            }
        }
    }

    private fun buildSpec(isStrongboxBacked: Boolean): KeyGenParameterSpec {
        val spec = KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(blockMode)
            .setEncryptionPaddings(padding)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .setIsStrongBoxBacked(isStrongboxBacked)
            .build()
        return spec
    }

    fun encrypt(data: ByteArray): ByteArray {
        val secretKey = keyStore.getKey(alias, password) as SecretKey

        val cipher = Cipher.getInstance(transformation).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val ciphertext = cipher.doFinal(data)

        val iv = cipher.iv
        check(iv.size == ivLength) { "Unexpected IV length: ${iv.size} ≠ $ivLength" }

        // Combine the IV and encrypted data into a single byte array
        return iv + ciphertext
    }

    fun decrypt(data: ByteArray): ByteArray {
        val secretKey = keyStore.getKey(alias, password) as SecretKey

        // Extract the IV from the beginning of the blob
        val iv = data.sliceArray(0 until ivLength)
        val actualEncryptedData = data.sliceArray(ivLength until data.size)

        val spec = GCMParameterSpec(128, iv)
        val cipher = Cipher.getInstance(transformation).apply { init(Cipher.DECRYPT_MODE, secretKey, spec) }

        val decryptedDataBytes = cipher.doFinal(actualEncryptedData)
        return decryptedDataBytes
    }
}
