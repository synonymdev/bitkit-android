package to.bitkit.wear.services

import com.synonym.vssclient.vssDeriveStoreId
import com.synonym.vssclient.vssGet
import com.synonym.vssclient.vssNewClientWithLnurlAuth
import com.synonym.vssclient.vssShutdownClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.wear.env.Env
import to.bitkit.wear.security.WalletSeed
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watch's only way to the phone's backups on VSS, and it can only read: there is no call here that stores,
 * replaces or deletes anything, so the watch can never clobber what the phone backed up.
 */
fun interface PhoneBackupReader {
    /** The backup object stored under [key] for the wallet of [seed], or null when the phone stored none. */
    suspend fun read(seed: WalletSeed, key: String): ByteArray?
}

/**
 * [PhoneBackupReader] over vss-client, set up as the phone's `VssBackupClient` is: the same server, store id and
 * LNURL-auth sign-in, all derived from the seed. This file is the only one that may name the VSS library, and of it
 * only the four calls imported above; `PhoneBackupReaderTest` checks both. The library keeps one client for the
 * process, holding keys derived from the seed, so each read builds it and shuts it down again: nothing of a wallet
 * stays behind for a lock, a pause or a wipe to clean up.
 */
@Singleton
class VssPhoneBackupReader @Inject constructor() : PhoneBackupReader {
    private val mutex = Mutex()

    override suspend fun read(seed: WalletSeed, key: String): ByteArray? = mutex.withLock {
        try {
            vssNewClientWithLnurlAuth(
                baseUrl = Env.vssServerUrl,
                storeId = vssDeriveStoreId(Env.vssStoreIdPrefix, seed.mnemonic, seed.passphrase),
                mnemonic = seed.mnemonic,
                passphrase = seed.passphrase,
                lnurlAuthServerUrl = Env.lnurlAuthServerUrl,
            )
            vssGet(key)?.value
        } finally {
            withContext(NonCancellable) { runCatching { vssShutdownClient() } }
        }
    }
}
