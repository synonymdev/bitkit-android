package to.bitkit.wear.security

import to.bitkit.wear.utils.AppError

/** Keeps the watch wallet's BIP39 seed. Implementations must never log or back up the seed. */
interface SeedStore {
    /** Whether a seed is stored, without reading it. */
    suspend fun exists(): Boolean

    /**
     * Reads the seed. Fails with [SeedStoreError.KeyInvalidated] when its key is gone or can never be used again. It
     * never deletes anything: the caller decides what a lost key means.
     */
    suspend fun load(): Result<WalletSeed>

    /**
     * Stores [seed] under a new key. Fails with [SeedStoreError.AlreadyExists] while a seed is stored, and with
     * [SeedStoreError.InsecureKey] when the key would not live in secure hardware.
     */
    suspend fun save(seed: WalletSeed): Result<Unit>

    /**
     * Deletes the seed and the key that protects it. It succeeds once the seed file is gone: a key that could not be
     * deleted protects nothing, and its delete is tried again at the next launch.
     */
    suspend fun wipe(): Result<Unit>
}

/** A BIP39 recovery phrase and its optional passphrase. [toString] never prints either. */
data class WalletSeed(
    val mnemonic: String,
    val passphrase: String?,
) {
    override fun toString(): String = "WalletSeed(***)"
}

sealed class SeedStoreError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    class NotFound : SeedStoreError("No wallet seed is stored")

    /** A seed is stored already; it has to be wiped before another one is saved. */
    class AlreadyExists : SeedStoreError("A wallet seed is stored already")

    /** The seed key only works while the watch is unlocked, and the watch is locked. */
    class DeviceLocked(cause: Throwable? = null) : SeedStoreError("The watch is locked", cause)

    /** The seed key is missing or permanently unusable: the stored seed can never be read again. */
    class KeyInvalidated(cause: Throwable? = null) : SeedStoreError("The seed key was invalidated", cause)

    class Corrupted(cause: Throwable? = null) : SeedStoreError("The stored seed cannot be read", cause)

    class Failed(cause: Throwable? = null) : SeedStoreError("The seed key operation failed", cause)

    /** The watch can only keep the seed key in software, where a release build never stores a seed. */
    class InsecureKey(backing: KeyBacking) : SeedStoreError("The seed key would be backed by '$backing'")
}
