package to.bitkit.data.sharing

import android.content.ContentProvider
import android.content.ContentValues
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
import to.bitkit.services.PaykitSdkService
import to.bitkit.utils.Logger

class SharedPubkyProvider : ContentProvider() {
    private companion object {
        const val TAG = "SharedPubkyProvider"
        const val EXPORT_ENABLED = "1"
        const val QUARANTINED = "1"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun keychain(): Keychain
    }

    private val keychain: Keychain by lazy {
        val applicationContext = requireNotNull(context?.applicationContext) {
            "SharedPubkyProvider context is unavailable"
        }
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).keychain()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceCaller()
        require(selection == null && selectionArgs == null && sortOrder == null) {
            "Selection and sorting are unsupported"
        }

        val route = ProviderRoute.parse(uri, requireNotNull(context).packageName)
        val expectedColumns = when (route) {
            ProviderRoute.Identities -> SharedPubkyContract.publicColumns
            is ProviderRoute.Credential -> SharedPubkyContract.credentialColumns
        }
        require(projection == null || projection.contentEquals(expectedColumns)) {
            "Unsupported shared Pubky projection"
        }

        val cursor = MatrixCursor(expectedColumns)
        val localIdentity = readLocalIdentity() ?: return cursor
        when (route) {
            ProviderRoute.Identities -> cursor.addRow(localIdentity.publicRow())
            is ProviderRoute.Credential -> {
                if (route.pubky == localIdentity.pubky) {
                    cursor.addRow(localIdentity.credentialRow())
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        val packageName = requireNotNull(context).packageName
        return when (ProviderRoute.parse(uri, packageName)) {
            ProviderRoute.Identities -> "vnd.android.cursor.dir/vnd.$packageName.sharedpubky.identity"
            is ProviderRoute.Credential -> "vnd.android.cursor.item/vnd.$packageName.sharedpubky.credential"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Shared Pubky provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Shared Pubky provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Shared Pubky provider is read-only")

    private fun enforceCaller() {
        val providerContext = requireNotNull(context)
        val caller = callingPackage
        val isCallerPackage = caller == SharedPubkyContract.RING_SOURCE &&
            caller in providerContext.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        val isCallerSignedByBitkit = caller != null &&
            providerContext.packageManager.checkSignatures(providerContext.packageName, caller) ==
            PackageManager.SIGNATURE_MATCH
        if (!isCallerPackage || !isCallerSignedByBitkit) {
            throw SecurityException("Caller is not trusted for shared Pubky access")
        }
    }

    private fun readLocalIdentity(): LocalIdentity? {
        val isExportEnabled = runCatching {
            keychain.loadString(Keychain.Key.PUBKY_SHARED_EXPORT_ENABLED.name)
        }.onFailure {
            Logger.warn("Failed to read shared Pubky export state", it, context = TAG)
        }.getOrNull() == EXPORT_ENABLED
        if (!isExportEnabled) return null

        val isManagedSecretQuarantined = runCatching {
            keychain.loadString(Keychain.Key.PUBKY_MANAGED_SECRET_QUARANTINED.name)
        }.onFailure {
            Logger.warn("Failed to read managed Pubky secret quarantine", it, context = TAG)
        }.getOrNull() == QUARANTINED

        val secretKeyHex = runCatching {
            keychain.loadString(Keychain.Key.PUBKY_SECRET_KEY.name)
        }.onFailure {
            Logger.warn("Failed to read local shared Pubky identity", it, context = TAG)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            localSharedPubkyIdentity(
                exportEnabled = true,
                managedSecretQuarantined = isManagedSecretQuarantined,
                secretKeyHex = secretKeyHex,
                publicKeyFromSecret = PaykitSdkService::publicKeyFromSecret,
            )
        }.onFailure {
            Logger.warn("Failed to validate local shared Pubky identity", it, context = TAG)
        }.getOrNull()
    }

    private sealed interface ProviderRoute {
        data object Identities : ProviderRoute
        data class Credential(val pubky: String) : ProviderRoute

        companion object {
            fun parse(uri: Uri, packageName: String): ProviderRoute {
                require(uri.scheme == "content" && uri.authority == "$packageName.sharedpubky") {
                    "Unsupported shared Pubky URI"
                }
                val segments = uri.pathSegments
                if (segments == listOf("v1", "identities")) return Identities
                if (
                    segments.size == 4 &&
                    segments.take(2) == listOf("v1", "identities") &&
                    segments.last() == "credential"
                ) {
                    return Credential(SharedPubkyContract.requireWirePubky(segments[2]))
                }
                throw IllegalArgumentException("Unsupported shared Pubky URI")
            }
        }
    }
}

internal fun localSharedPubkyIdentity(
    exportEnabled: Boolean,
    managedSecretQuarantined: Boolean,
    secretKeyHex: String?,
    publicKeyFromSecret: (String) -> String,
): LocalIdentity? {
    if (!exportEnabled || managedSecretQuarantined || secretKeyHex.isNullOrBlank()) return null
    val canonicalSecretKeyHex = SharedPubkyContract.canonicalSecretKeyHex(secretKeyHex)
    val pubky = SharedPubkyContract.canonicalPubky(publicKeyFromSecret(canonicalSecretKeyHex))
    return LocalIdentity(pubky = pubky, secretKeyHex = canonicalSecretKeyHex)
}

internal class LocalIdentity(
    val pubky: String,
    private val secretKeyHex: String,
) {
    fun publicRow(): Array<Any?> = arrayOf(
        SharedPubkyContract.PROTOCOL_VERSION,
        SharedPubkyContract.BITKIT_SOURCE,
        pubky,
    )

    fun credentialRow(): Array<Any?> = arrayOf(
        SharedPubkyContract.PROTOCOL_VERSION,
        SharedPubkyContract.BITKIT_SOURCE,
        pubky,
        secretKeyHex,
    )
}
