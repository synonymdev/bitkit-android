package to.bitkit.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import to.bitkit.async.appScope
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.repositories.PubkyRepo
import to.bitkit.utils.Logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advertises Pubky signup and authorization handlers when their required identity state is available.
 * While a saved session is not restored yet, a secret key Bitkit stores itself keeps the authorization handler on, so a
 * sign-in link can wait for the restore instead of being dropped. Approval still checks the restored identity's key.
 */
@Singleton
internal class PubkyAuthHandlerRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val settingsStore: SettingsStore,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "PubkyAuthHandlerRegistrar"
        private const val PUBKY_AUTH_ALIAS_CLASS = "to.bitkit.ui.MainActivityPubkyAuth"

        /** Handles signup links before a Pubky identity is available. */
        private const val PUBKY_SIGNUP_ALIAS_CLASS = "to.bitkit.ui.MainActivityPubkySignup"
    }

    private val scope: CoroutineScope = appScope(ioDispatcher, TAG)
    private val aliasComponent = ComponentName(context.packageName, PUBKY_AUTH_ALIAS_CLASS)
    private val signupAliasComponent = ComponentName(context.packageName, PUBKY_SIGNUP_ALIAS_CLASS)
    private val started = AtomicBoolean()

    fun start() = start(scope)

    internal fun start(collectionScope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return

        collectionScope.launch {
            pubkyRepo.awaitInitialization()
            combine(
                settingsStore.isPaykitEnabled,
                pubkyRepo.publicKey,
                pubkyRepo.backupStateVersion,
                pubkyRepo.identityRefreshVersion,
            ) { localFlagEnabled, publicKey, _, _ ->
                val hasIdentity = runSuspendCatching { pubkyRepo.hasIdentity() }
                    .onFailure { Logger.warn("Failed to read saved Pubky identity", it, context = TAG) }
                    .getOrDefault(true)
                val isPaykitUiEnabled = PaykitFeatureFlags.isUiEnabled(localFlagEnabled)
                val hasSecretKey = isPaykitUiEnabled && hasLocalSecretKey(publicKey)
                val authEnabled = canHandlePubkyAuth(isPaykitUiEnabled, hasIdentity, hasSecretKey)
                authEnabled to (isPaykitUiEnabled && !hasIdentity)
            }
                .distinctUntilChanged()
                .collectLatest { (authEnabled, signupEnabled) ->
                    setAliasEnabled(aliasComponent, authEnabled)
                    setAliasEnabled(signupAliasComponent, signupEnabled)
                }
        }
    }

    private suspend fun hasLocalSecretKey(publicKey: String?): Boolean {
        if (publicKey != null) return pubkyRepo.hasSecretKey()
        return runSuspendCatching { pubkyRepo.hasLocalSecretKey() }
            .onFailure { Logger.warn("Failed to read saved Pubky secret key", it, context = TAG) }
            .getOrDefault(false)
    }

    private fun setAliasEnabled(component: ComponentName, enabled: Boolean) {
        val state =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        }.onSuccess {
            Logger.info(
                "Updated Pubky handler '${component.className}' to '${if (enabled) "enabled" else "disabled"}'",
                context = TAG,
            )
        }.onFailure {
            Logger.error("Failed to update Pubky handler '${component.className}'", it, context = TAG)
        }
    }
}

internal fun canHandlePubkyAuth(
    isPaykitUiEnabled: Boolean,
    hasIdentity: Boolean,
    hasSecretKey: Boolean,
): Boolean = isPaykitUiEnabled && hasIdentity && hasSecretKey
