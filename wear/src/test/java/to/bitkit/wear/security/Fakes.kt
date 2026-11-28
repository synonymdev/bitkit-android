package to.bitkit.wear.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import to.bitkit.wear.data.ForegroundMonitor
import to.bitkit.wear.data.NetworkMonitor
import to.bitkit.wear.data.NfcMonitor
import to.bitkit.wear.data.NfcState
import to.bitkit.wear.ext.runSuspendCatching

/** A [Bip39] over a few words, whose phrases are valid when they end in "about". */
class FakeBip39 : Bip39 {
    private val words = listOf("abandon", "ability", "able", "about", "above", "act", "action", "zoo")

    override fun isWord(word: String): Boolean = word in words

    override fun suggestions(prefix: String): List<String> =
        if (prefix.length < Bip39.MIN_PREFIX_LENGTH) emptyList() else words.filter { it.startsWith(prefix) }

    override fun validate(words: List<String>): Bip39Validation {
        val unknown = words.indexOfFirst { !isWord(it) }
        return when {
            unknown >= 0 -> Bip39Validation.UnknownWord(unknown)
            words.lastOrNull() == "about" -> Bip39Validation.Valid
            else -> Bip39Validation.InvalidChecksum
        }
    }

    override fun toSeed(mnemonic: String, passphrase: String?): ByteArray =
        "$mnemonic|${passphrase.orEmpty()}".encodeToByteArray()
}

class FakeForegroundMonitor(isForeground: Boolean = true) : ForegroundMonitor {
    override val isForeground = MutableStateFlow(isForeground)
}

class FakeNetworkMonitor(isOnline: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(isOnline)
}

class FakeNfcMonitor(state: NfcState = NfcState.On) : NfcMonitor {
    override val state = MutableStateFlow(state)
}

class FakeDeviceLock(
    override var isSecure: Boolean = true,
    isLocked: Boolean = false,
) : DeviceLock {
    override val isLocked = MutableStateFlow(isLocked)
    var pendingIsLocked: Boolean? = null

    override fun refresh() {
        pendingIsLocked?.let { pending -> isLocked.update { pending } }
    }
}

/** A [SeedStore] that keeps the seed in memory; [onWipe] runs before each wipe, to fail it or stop it. */
class FakeSeedStore(var seed: WalletSeed? = null) : SeedStore {
    var onWipe: () -> Unit = {}

    override suspend fun exists(): Boolean = seed != null

    override suspend fun load(): Result<WalletSeed> =
        seed?.let { Result.success(it) } ?: Result.failure(SeedStoreError.NotFound())

    override suspend fun save(seed: WalletSeed): Result<Unit> {
        if (this.seed != null) return Result.failure(SeedStoreError.AlreadyExists())
        this.seed = seed
        return Result.success(Unit)
    }

    override suspend fun wipe(): Result<Unit> = runSuspendCatching {
        onWipe()
        seed = null
    }
}

/** A [SeedKey] that keeps its key in memory and can be told to fail the way the platform Keystore does. */
class FakeSeedKey : SeedKey {
    private var key: Byte? = null
    private var counter = 0
    var failure: SeedStoreError? = null

    /** How many of the next decrypts report a lost key before the key works again. */
    var lostKeyReads = 0
    var decryptCount = 0
    var createCount = 0
    var isUnlockedDeviceRequired: Boolean? = null
    var backing = KeyBacking.TrustedEnvironment

    /** How many of the next deletes fail before the key can be deleted again. */
    var failingDeletes = 0
    var deleteCount = 0
    val plaintexts = mutableListOf<ByteArray>()

    /** The key disappears, as when the system deletes it. */
    fun invalidate() {
        key = null
    }

    fun exists(): Boolean = key != null

    override fun create(isUnlockedDeviceRequired: Boolean): KeyBacking {
        failure?.let { throw it }
        this.isUnlockedDeviceRequired = isUnlockedDeviceRequired
        createCount++
        key = (++counter).toByte()
        return backing
    }

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        failure?.let { throw it }
        plaintexts += plaintext
        return aad + xor(plaintext)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        decryptCount++
        if (lostKeyReads-- > 0) throw SeedStoreError.KeyInvalidated()
        failure?.let { throw it }
        if (!blob.copyOf(aad.size).contentEquals(aad)) throw SeedStoreError.Corrupted()
        return xor(blob.copyOfRange(aad.size, blob.size))
    }

    override fun delete() {
        deleteCount++
        if (failingDeletes-- > 0) throw SeedStoreError.Failed()
        key = null
    }

    private fun xor(bytes: ByteArray): ByteArray {
        val mask = key ?: throw SeedStoreError.KeyInvalidated()
        return ByteArray(bytes.size) { (bytes[it].toInt() xor mask.toInt() xor 0x5A).toByte() }
    }
}
