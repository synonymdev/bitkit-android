package to.bitkit.data.backup

import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.LdkNamespace
import com.synonym.vssclient.VssItem
import com.synonym.vssclient.vssDeleteLdk
import com.synonym.vssclient.vssGetLdk
import com.synonym.vssclient.vssListKeysLdk
import com.synonym.vssclient.vssListLdk
import com.synonym.vssclient.vssStoreLdk
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.utils.Logger

class VssBackupClientLdk @AssistedInject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @Assisted private val awaitSetup: suspend () -> Unit,
) {
    companion object {
        private const val TAG = "VssBackupClientLdk"
    }

    @AssistedFactory
    interface Factory {
        fun create(awaitSetup: suspend () -> Unit): VssBackupClientLdk
    }

    suspend fun getObject(
        key: String,
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<VssItem?> = withContext(ioDispatcher) {
        awaitSetup()
        Logger.verbose("VSS LDK 'getObject' call for '$key'", context = TAG)
        runCatching {
            vssGetLdk(key = key, namespace = namespace)
        }.onSuccess {
            if (it == null) {
                Logger.verbose("VSS LDK 'getObject' success null for '$key'", context = TAG)
            } else {
                Logger.verbose("VSS LDK 'getObject' success for '$key'", context = TAG)
            }
        }.onFailure {
            Logger.verbose("VSS LDK 'getObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun putObject(
        key: String,
        data: ByteArray,
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<VssItem> = withContext(ioDispatcher) {
        awaitSetup()
        Logger.verbose("VSS LDK 'putObject' call for '$key'", context = TAG)
        runCatching {
            vssStoreLdk(key = key, value = data, namespace = namespace)
        }.onSuccess {
            Logger.verbose("VSS LDK 'putObject' success for '$key' at version: '${it.version}'", context = TAG)
        }.onFailure {
            Logger.verbose("VSS LDK 'putObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun deleteObject(
        key: String,
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<Boolean> = withContext(ioDispatcher) {
        awaitSetup()
        Logger.verbose("VSS LDK 'deleteObject' call for '$key'", context = TAG)
        runCatching {
            vssDeleteLdk(key = key, namespace = namespace)
        }.onSuccess { wasDeleted ->
            if (wasDeleted) {
                Logger.verbose("VSS LDK 'deleteObject' success for '$key' - key was deleted", context = TAG)
            } else {
                Logger.verbose("VSS LDK 'deleteObject' success for '$key' - key did not exist", context = TAG)
            }
        }.onFailure {
            Logger.verbose("VSS LDK 'deleteObject' error for '$key'", it, context = TAG)
        }
    }

    suspend fun listKeys(
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<List<KeyVersion>> = withContext(ioDispatcher) {
        awaitSetup()
        Logger.verbose("VSS LDK 'listKeys' call", context = TAG)
        runCatching {
            vssListKeysLdk(namespace = namespace)
        }.onSuccess {
            Logger.verbose("VSS LDK 'listKeys' success - found ${it.size} key(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS LDK 'listKeys' error", it, context = TAG)
        }
    }

    suspend fun listItems(
        namespace: LdkNamespace = LdkNamespace.Default,
    ): Result<List<VssItem>> = withContext(ioDispatcher) {
        awaitSetup()
        Logger.verbose("VSS LDK 'listItems' call", context = TAG)
        runCatching {
            vssListLdk(namespace = namespace)
        }.onSuccess {
            Logger.verbose("VSS LDK 'listItems' success - found ${it.size} item(s)", context = TAG)
        }.onFailure {
            Logger.verbose("VSS LDK 'listItems' error", it, context = TAG)
        }
    }
}
