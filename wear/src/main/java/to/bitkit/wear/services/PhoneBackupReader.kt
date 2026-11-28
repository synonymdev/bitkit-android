package to.bitkit.wear.services

import com.synonym.vssclient.VssException
import com.synonym.vssclient.vssDeriveStoreId
import com.synonym.vssclient.vssGet
import com.synonym.vssclient.vssNewClientWithLnurlAuth
import com.synonym.vssclient.vssShutdownClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.wear.env.Env
import to.bitkit.wear.ext.runSuspendCatching
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.utils.AppError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watch's only way to the phone's backups on VSS, and it can only read: there is no call here that stores,
 * replaces or deletes anything, so the watch can never clobber what the phone backed up.
 */
fun interface PhoneBackupReader {
    /**
     * The backup object stored under [key] for the wallet of [seed], or null when the phone stored none. Fails with
     * [PhoneBackupUnreadable] when an object is stored that this wallet's keys cannot open.
     */
    suspend fun read(seed: WalletSeed, key: String): ByteArray?
}

/** The phone's backup object is stored, but it cannot be decoded or decrypted with the wallet's keys. */
class PhoneBackupUnreadable : AppError("The phone's backup cannot be decrypted")

/**
 * [PhoneBackupReader] over vss-client, set up as the phone's `VssBackupClient` is: the same server, store id and
 * LNURL-auth sign-in, all derived from the seed. This file is the only one that may name the VSS library, and of it
 * only the four calls and the error type imported above; `PhoneBackupReaderTest` checks both. The library reports
 * an object it fetched and cannot decode or decrypt as a get error, and every failure to reach the server or to
 * sign in as another type, so a get error is an unreadable backup. The library keeps one client for the
 * process, holding keys derived from the seed, so each read builds it and shuts it down again: nothing of a wallet
 * stays behind for a lock, a pause or a wipe to clean up.
 *
 * The library runs each call to its end on the calling thread, so a read cannot be interrupted: one that is
 * cancelled goes on until its HTTP call returns and only then shuts the client down. A caller that must not wait
 * that long runs the read apart and stops waiting for it, as `SettingsSyncRepo` does.
 */
@Singleton
class VssPhoneBackupReader @Inject constructor() : PhoneBackupReader {
    private val mutex = Mutex()

    override suspend fun read(seed: WalletSeed, key: String): ByteArray? = mutex.withLock {
        try {
            vssNewClientWithLnurlAuth(
                baseUrl = Env.vssServerUrl(),
                storeId = vssDeriveStoreId(Env.vssStoreIdPrefix(), seed.mnemonic, seed.passphrase),
                mnemonic = seed.mnemonic,
                passphrase = seed.passphrase,
                lnurlAuthServerUrl = Env.lnurlAuthServerUrl(),
            )
            try {
                vssGet(key)?.value
            } catch (_: VssException.GetException) {
                throw PhoneBackupUnreadable()
            }
        } finally {
            withContext(NonCancellable) { runSuspendCatching { vssShutdownClient() } }
        }
    }
}
