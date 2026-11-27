package to.bitkit.wear.security

import javax.inject.Inject

/**
 * Placeholder [SeedStore] that holds no seed: every call fails or reports none. Replace its binding in
 * [to.bitkit.wear.di.SecurityModule] with the Keystore-backed store.
 */
class UnimplementedSeedStore @Inject constructor() : SeedStore {
    override suspend fun exists(): Boolean = false

    override suspend fun load(): Result<WalletSeed> = Result.failure(SeedStoreError.NotImplemented())

    override suspend fun save(seed: WalletSeed): Result<Unit> = Result.failure(SeedStoreError.NotImplemented())

    override suspend fun wipe(): Result<Unit> = Result.failure(SeedStoreError.NotImplemented())
}
