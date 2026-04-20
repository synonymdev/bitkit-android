package to.bitkit.dev

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.async.ServiceQueue
import to.bitkit.repositories.LightningRepo
import to.bitkit.utils.Logger

private const val TAG = "DevToolsProvider"

class DevToolsProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun lightningRepo(): LightningRepo
    }

    private val deps: Dependencies by lazy {
        val ctx = requireNotNull(context) { "DevToolsProvider context is null" }
        EntryPointAccessors.fromApplication(ctx, Dependencies::class.java)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.SHELL_UID) { "Only ADB shell callers are allowed" }
        return runCatching {
            val command = requireNotNull(DevCommand.parse(method, arg)) { "Unknown command: '$method'" }
            ServiceQueue.LDK.blocking { command.execute(deps) }
        }.getOrElse {
            Logger.error("Failed to execute command '$method'", it, context = TAG)
            DevResult.Error(it.message)
        }.toBundle()
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?) = 0
    override fun query(uri: Uri, proj: Array<String>?, sel: String?, args: Array<String>?, sort: String?) = null
}

private sealed interface DevCommand {

    companion object {
        fun parse(method: String, arg: String?): DevCommand? = when (method) {
            CreateInvoice.METHOD -> CreateInvoice.parse(arg)
            else -> null
        }
    }

    suspend fun execute(deps: DevToolsProvider.Dependencies): DevResult

    data class CreateInvoice(val args: Args) : DevCommand {
        companion object {
            const val METHOD = "createInvoice"
            fun parse(arg: String?) = CreateInvoice(arg.deserialize<Args>())
        }

        @Serializable
        data class Args(val amount: ULong? = null, val description: String = "dev-invoice")

        override suspend fun execute(deps: DevToolsProvider.Dependencies) =
            deps.lightningRepo().createInvoice(args.amount, args.description).fold(
                onSuccess = { DevResult.Invoice(it) },
                onFailure = {
                    Logger.error("Failed to create invoice", it, context = TAG)
                    DevResult.Error(it.message)
                },
            )
    }
}

@Serializable
private sealed interface DevResult {

    companion object {
        private const val KEY_RESULT = "result"
    }

    @Serializable data class Invoice(val bolt11: String) : DevResult

    @Serializable data class Error(val message: String? = null) : DevResult

    fun toBundle() = bundleOf(KEY_RESULT to Json.encodeToString(this))
}

private inline fun <reified T> String?.deserialize(): T =
    if (isNullOrBlank()) Json.decodeFromString("{}") else Json.decodeFromString(this)
