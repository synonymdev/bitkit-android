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
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.repositories.PubkyRepo
import to.bitkit.utils.Logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Advertises Pubky signup and authorization handlers when their required identity state is available. */
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
            combine(settingsStore.isPaykitEnabled, pubkyRepo.publicKey) { localFlagEnabled, publicKey ->
                localFlagEnabled to publicKey
            }
                .distinctUntilChanged()
                .collectLatest { (localFlagEnabled, publicKey) ->
                    val isPaykitUiEnabled = PaykitFeatureFlags.isUiEnabled(localFlagEnabled)
                    val hasIdentity = publicKey != null
                    val hasSecretKey = isPaykitUiEnabled && hasIdentity && pubkyRepo.hasSecretKey()

                    setAliasEnabled(
                        aliasComponent,
                        canHandlePubkyAuth(
                            isPaykitUiEnabled = isPaykitUiEnabled,
                            hasIdentity = hasIdentity,
                            hasSecretKey = hasSecretKey,
                        ),
                    )
                    setAliasEnabled(signupAliasComponent, isPaykitUiEnabled && !hasIdentity)
                }
        }
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
