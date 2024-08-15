package to.bitkit.data.keychain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
    private val padding = KeyProperties.ENCRYPTION_PADDING_PKCS7
    private val transformation = "$algorithm/$blockMode/$padding"

    private val ivLength = 12 // GCM typically uses a 12-byte IV

    private val keyStore by lazy { KeyStore.getInstance(type).apply { load(null) } }

    init {
        generateKey()
    }

    private fun generateKey() {
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(algorithm, type)
            val spec = KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(blockMode)
                .setEncryptionPaddings(padding)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
    }

    fun encrypt(data: String): ByteArray {
        val secretKey = keyStore.getKey(alias, password) as SecretKey
        val cipher = Cipher.getInstance(transformation).apply { init(Cipher.ENCRYPT_MODE, secretKey) }

        val encryptedData = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == ivLength) { "Unexpected IV length: ${iv.size} ≠ $ivLength" }

        // Combine the IV and encrypted data into a single byte array
        return iv + encryptedData
    }

    fun decrypt(data: ByteArray): String {
        val secretKey = keyStore.getKey(alias, password) as SecretKey

        // Extract the IV from the beginning of the encrypted data
        val iv = data.sliceArray(0 until ivLength)
        val actualEncryptedData = data.sliceArray(ivLength until data.size)

        val spec = GCMParameterSpec(128, iv)
        val cipher = Cipher.getInstance(transformation).apply { init(Cipher.DECRYPT_MODE, secretKey, spec) }

        val decryptedDataBytes = cipher.doFinal(actualEncryptedData)
        return decryptedDataBytes.toString(Charsets.UTF_8)
    }
}
