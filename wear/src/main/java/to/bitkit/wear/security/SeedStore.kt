package to.bitkit.wear.security

import to.bitkit.wear.utils.AppError

/** Keeps the watch wallet's BIP39 seed. Implementations must never log or back up the seed. */
interface SeedStore {
    /** Whether a seed is stored, without reading it. */
    suspend fun exists(): Boolean

    /**
     * Reads the seed. Fails with [SeedStoreError.KeyInvalidated] when its key is gone or can never be used again,
     * after deleting what is left of the seed.
     */
    suspend fun load(): Result<WalletSeed>

    /** Stores [seed] under a new key, replacing any seed stored before. */
    suspend fun save(seed: WalletSeed): Result<Unit>

    /** Deletes the seed and the key that protects it. */
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

    /** The seed key only works while the watch is unlocked, and the watch is locked. */
    class DeviceLocked(cause: Throwable? = null) : SeedStoreError("The watch is locked", cause)

    /** The seed key is missing or permanently unusable: the stored seed can never be read again. */
    class KeyInvalidated(cause: Throwable? = null) : SeedStoreError("The seed key was invalidated", cause)

    class Corrupted(cause: Throwable? = null) : SeedStoreError("The stored seed cannot be read", cause)

    class Failed(cause: Throwable? = null) : SeedStoreError("The seed key operation failed", cause)
}
