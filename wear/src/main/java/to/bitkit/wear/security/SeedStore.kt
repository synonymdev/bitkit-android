package to.bitkit.wear.security

import to.bitkit.wear.utils.AppError

/** Keeps the watch wallet's BIP39 seed. Implementations must never log or back up the seed. */
interface SeedStore {
    /** Whether a seed is stored, without reading it. */
    suspend fun exists(): Boolean

    suspend fun load(): Result<WalletSeed>

    suspend fun save(seed: WalletSeed): Result<Unit>

    /** Deletes the seed and any key that protects it. */
    suspend fun wipe(): Result<Unit>
}

/** A BIP39 recovery phrase and its optional passphrase. [toString] never prints either. */
data class WalletSeed(
    val mnemonic: String,
    val passphrase: String?,
) {
    override fun toString(): String = "WalletSeed(***)"
}

sealed class SeedStoreError(message: String) : AppError(message) {
    class NotImplemented : SeedStoreError("Seed storage is not implemented yet")
}
