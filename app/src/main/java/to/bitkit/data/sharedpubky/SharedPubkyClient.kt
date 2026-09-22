package to.bitkit.data.sharedpubky

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.data.sharedpubky.SharedPubkyContract.COLUMN_PUBKY
import to.bitkit.data.sharedpubky.SharedPubkyContract.COLUMN_SECRET_KEY
import to.bitkit.data.sharedpubky.SharedPubkyContract.PATH_CREDENTIAL
import to.bitkit.data.sharedpubky.SharedPubkyContract.PATH_IDENTITIES
import to.bitkit.data.sharedpubky.SharedPubkyContract.RING_AUTHORITY
import to.bitkit.data.sharedpubky.SharedPubkyContract.RING_PACKAGE
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SharedPubkyClient"

@Singleton
class SharedPubkyClient @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun listRingIdentities(): Result<ImmutableList<String>> = withContext(ioDispatcher) {
        runSuspendCatching {
            if (!isRingProviderTrusted()) return@runSuspendCatching persistentListOf()

            val uri = Uri.parse("content://$RING_AUTHORITY/$PATH_IDENTITIES")
            val cursor = requireNotNull(context.contentResolver.query(uri, null, null, null, null)) {
                "Ring identities query returned no cursor"
            }
            cursor.use {
                buildList {
                    val column = it.getColumnIndexOrThrow(COLUMN_PUBKY)
                    while (it.moveToNext()) {
                        it.getString(column)?.let(::add)
                    }
                }
            }.toImmutableList()
        }
    }

    suspend fun ringCredential(pubky: String): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            requireNotNull(readCredential(pubky)) { "Ring credential unavailable" }
        }
    }

    internal fun readCredential(pubky: String): String? {
        if (!isRingProviderTrusted()) return null

        val uri = Uri.parse("content://$RING_AUTHORITY/$PATH_IDENTITIES/$pubky/$PATH_CREDENTIAL")
        val cursor = requireNotNull(context.contentResolver.query(uri, null, null, null, null)) {
            "Ring credential query returned no cursor"
        }
        return cursor.use {
            if (it.count != 1 || !it.moveToFirst()) return null
            val rowPubky = it.getString(it.getColumnIndexOrThrow(COLUMN_PUBKY))
            val secretKeyHex = it.getString(it.getColumnIndexOrThrow(COLUMN_SECRET_KEY)).orEmpty()

            if (!PubkyPublicKeyFormat.matches(rowPubky, pubky)) return null
            secretKeyHex.takeIf { hex -> SharedPubkyContract.isValidSecret(hex, pubky) }
        }
    }

    private fun isRingProviderTrusted(): Boolean {
        val packageManager = context.packageManager
        val isTrusted = packageManager.resolveContentProvider(RING_AUTHORITY, 0)?.packageName == RING_PACKAGE &&
            packageManager.checkSignatures(context.packageName, RING_PACKAGE) == PackageManager.SIGNATURE_MATCH

        if (!isTrusted) Logger.warn("Skipped shared pubky query, ring provider unavailable", context = TAG)
        return isTrusted
    }
}
