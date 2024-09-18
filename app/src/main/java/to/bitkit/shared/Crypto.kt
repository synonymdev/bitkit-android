package to.bitkit.shared

import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import to.bitkit.ext.hex
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import javax.crypto.KeyAgreement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Crypto @Inject constructor() {
    @Suppress("ArrayInDataClass")
    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    private val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")

    init {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        when {
            provider == null -> Security.addProvider(BouncyCastleProvider())
            provider::class.java != BouncyCastleProvider::class.java -> {
                // We substitute the outdated BC provider registered in Android
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC")
        keyPairGenerator.initialize(ecSpec)
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = (keyPair.private as BCECPrivateKey).d.toByteArray().let {
            if (it.size == 33 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it // ensures 32 bytes privateKey
        }
        val publicKey = (keyPair.public as BCECPublicKey).q.getEncoded(true)

        return KeyPair(
            privateKey = privateKey,
            publicKey = publicKey,
        )
    }

    fun generateSharedSecret(
        privateKeyBytes: ByteArray,
        nodePubkey: String,
        derivationName: String? = null,
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC", "BC")
        val privateKey = keyFactory.generatePrivate(ECPrivateKeySpec(BigInteger(1, privateKeyBytes), ecSpec))
        val publicKey = keyFactory.generatePublic(ECPublicKeySpec(ecSpec.curve.decodePoint(nodePubkey.hex), ecSpec))

        val baseSecret = KeyAgreement.getInstance("ECDH", "BC").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }

        if (derivationName != null) {
            val bytes = derivationName.toByteArray()
            val merged = baseSecret + bytes
            return sha256d(merged)
        }

        return baseSecret
    }

    private fun sha256d(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(input))
    }
}
