package to.bitkit.data.sharedpubky

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.sharedpubky.SharedPubkyContract.AUTHORITY_SUFFIX
import to.bitkit.data.sharedpubky.SharedPubkyContract.COLUMN_PUBKY
import to.bitkit.data.sharedpubky.SharedPubkyContract.COLUMN_SECRET_KEY
import to.bitkit.data.sharedpubky.SharedPubkyContract.PATH_CREDENTIAL
import to.bitkit.data.sharedpubky.SharedPubkyContract.PATH_IDENTITIES
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger

private const val TAG = "SharedPubkyProvider"
private const val MATCH_IDENTITIES = 1
private const val MATCH_CREDENTIAL = 2
private const val MIME_SUBTYPE = "vnd.to.bitkit.sharedpubky"
private const val PUBKY_PATH_INDEX = 2

class SharedPubkyProvider : ContentProvider() {
    internal var keychainProvider: () -> Keychain? = {
        context?.applicationContext?.let {
            EntryPointAccessors.fromApplication(it, SharedPubkyEntryPoint::class.java).keychain()
        }
    }

    internal var derivePublicKey: (String) -> String = PaykitSdkService::publicKeyFromSecret

    private val uriMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            val authority = "${context?.packageName}$AUTHORITY_SUFFIX"
            addURI(authority, PATH_IDENTITIES, MATCH_IDENTITIES)
            addURI(authority, "$PATH_IDENTITIES/*/$PATH_CREDENTIAL", MATCH_CREDENTIAL)
        }
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!isCallerTrusted(Binder.getCallingUid())) throw SecurityException("Caller signature mismatch")

        val match = uriMatcher.match(uri)
        val cursor = when (match) {
            MATCH_IDENTITIES -> MatrixCursor(arrayOf(COLUMN_PUBKY))
            MATCH_CREDENTIAL -> MatrixCursor(arrayOf(COLUMN_PUBKY, COLUMN_SECRET_KEY))
            else -> return null
        }

        val secretKeyHex = loadSecretKey().getOrElse { return null } ?: return cursor
        val pubky = SharedPubkyContract.pubkyFromSecret(secretKeyHex, derivePublicKey) ?: return cursor

        when (match) {
            MATCH_IDENTITIES -> cursor.addRow(arrayOf(pubky))
            MATCH_CREDENTIAL -> if (PubkyPublicKeyFormat.matches(uri.pathSegments.getOrNull(PUBKY_PATH_INDEX), pubky)) {
                cursor.addRow(arrayOf(pubky, secretKeyHex))
            }
        }
        Logger.debug("Served shared pubky '${PubkyPublicKeyFormat.redacted(pubky)}'", context = TAG)

        return cursor
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        MATCH_IDENTITIES -> "vnd.android.cursor.dir/$MIME_SUBTYPE"
        MATCH_CREDENTIAL -> "vnd.android.cursor.item/$MIME_SUBTYPE"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    internal fun isCallerTrusted(uid: Int): Boolean {
        val context = context ?: return false
        val packageManager = context.packageManager
        val callerPackages = packageManager.getPackagesForUid(uid).orEmpty()

        return callerPackages.isNotEmpty() && callerPackages.all {
            packageManager.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH
        }
    }

    private fun loadSecretKey(): Result<String?> =
        runCatching { keychainProvider()?.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)?.takeIf { it.isNotBlank() } }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SharedPubkyEntryPoint {
    fun keychain(): Keychain
}
