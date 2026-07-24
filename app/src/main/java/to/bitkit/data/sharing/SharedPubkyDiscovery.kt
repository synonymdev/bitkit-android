package to.bitkit.data.sharing

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedPubkyDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun discoverRingIdentities(): Result<List<SharedPubkyIdentity>> = runSuspendCatching {
        withContext(ioDispatcher) {
            verifyRingProvider()
            context.contentResolver.query(
                SharedPubkyContract.ringIdentitiesUri,
                SharedPubkyContract.publicColumns,
                null,
                null,
                null,
            )?.use(::readPublicIdentities) ?: throw SharedPubkyError.SourceUnavailable
        }
    }

    suspend fun readRingCredential(pubky: String): Result<SharedPubkyCredential> = runSuspendCatching {
        withContext(ioDispatcher) {
            verifyRingProvider()
            val expectedPubky = SharedPubkyContract.canonicalPubky(pubky)
            context.contentResolver.query(
                SharedPubkyContract.ringCredentialUri(expectedPubky),
                SharedPubkyContract.credentialColumns,
                null,
                null,
                null,
            )?.use { readCredential(it, expectedPubky) } ?: throw SharedPubkyError.IdentityUnavailable
        }
    }

    @Suppress("ThrowsCount")
    private fun verifyRingProvider() {
        val packageManager = context.packageManager
        val provider = packageManager.resolveContentProvider(
            SharedPubkyContract.RING_AUTHORITY,
            PackageManager.MATCH_ALL,
        ) ?: throw SharedPubkyError.SourceUnavailable
        if (provider.packageName != SharedPubkyContract.RING_SOURCE) {
            throw SharedPubkyError.UntrustedSource(provider.packageName)
        }
        if (
            provider.authority != SharedPubkyContract.RING_AUTHORITY ||
            provider.readPermission != SharedPubkyContract.RING_READ_PERMISSION
        ) {
            throw SharedPubkyError.UntrustedSource(provider.packageName)
        }
        if (
            packageManager.checkSignatures(context.packageName, provider.packageName) !=
            PackageManager.SIGNATURE_MATCH
        ) {
            throw SharedPubkyError.UntrustedSource(provider.packageName)
        }
    }

    private fun readPublicIdentities(cursor: Cursor): List<SharedPubkyIdentity> {
        val columns = cursor.requiredPublicColumns()
        val identities = buildList {
            while (cursor.moveToNext()) {
                add(cursor.readIdentity(columns))
            }
        }
        return identities.distinctBy { it.pubky }
    }

    @Suppress("ThrowsCount")
    private fun readCredential(cursor: Cursor, expectedPubky: String): SharedPubkyCredential {
        val publicColumns = cursor.requiredPublicColumns()
        val secretKeyColumn = cursor.getColumnIndex(SharedPubkyContract.COLUMN_SECRET_KEY)
        if (secretKeyColumn < 0 || !cursor.moveToFirst()) throw SharedPubkyError.IdentityUnavailable

        val identity = cursor.readIdentity(publicColumns)
        if (identity.pubky != expectedPubky || cursor.moveToNext()) {
            throw SharedPubkyError.InvalidResponse
        }
        return runCatching {
            SharedPubkyCredential(
                identity = identity,
                secretKeyHex = cursor.getString(secretKeyColumn).orEmpty(),
            )
        }.getOrElse {
            throw SharedPubkyError.InvalidResponse
        }
    }

    private fun Cursor.requiredPublicColumns() = PublicColumnIndexes(
        protocolVersion = getColumnIndex(SharedPubkyContract.COLUMN_PROTOCOL_VERSION),
        sourcePackage = getColumnIndex(SharedPubkyContract.COLUMN_SOURCE_PACKAGE),
        pubky = getColumnIndex(SharedPubkyContract.COLUMN_PUBKY),
    ).also {
        if (it.protocolVersion < 0 || it.sourcePackage < 0 || it.pubky < 0) {
            throw SharedPubkyError.InvalidResponse
        }
    }

    @Suppress("ThrowsCount")
    private fun Cursor.readIdentity(columns: PublicColumnIndexes): SharedPubkyIdentity {
        val version = getInt(columns.protocolVersion)
        if (version != SharedPubkyContract.PROTOCOL_VERSION) {
            throw SharedPubkyError.UnsupportedVersion(version)
        }
        val sourcePackage = getString(columns.sourcePackage).orEmpty()
        if (sourcePackage != SharedPubkyContract.RING_SOURCE) {
            throw SharedPubkyError.UntrustedSource(sourcePackage)
        }
        val pubky = runCatching {
            SharedPubkyContract.requireWirePubky(getString(columns.pubky).orEmpty())
        }.getOrElse {
            throw SharedPubkyError.InvalidResponse
        }
        return SharedPubkyIdentity(
            protocolVersion = version,
            sourcePackage = sourcePackage,
            pubky = pubky,
        )
    }
}

private data class PublicColumnIndexes(
    val protocolVersion: Int,
    val sourcePackage: Int,
    val pubky: Int,
)
