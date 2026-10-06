package to.bitkit.ui.screens.wallets.send

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.VssStoreIdProvider
import to.bitkit.env.Env
import to.bitkit.models.ActiveOnchainAttemptBackup
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.OnchainSendAttemptStore
import to.bitkit.repositories.OnchainSendEvidence
import to.bitkit.repositories.PendingPaymentRepo
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.LocalSettingsViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.SettingsViewModel
import java.io.File
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Owned-device integration: exact observation is injected after an independent read-only backend check. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SendPendingObservationDeviceTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Inject lateinit var store: OnchainSendAttemptStore

    @Inject lateinit var lightningRepo: LightningRepo

    @Inject lateinit var activityRepo: ActivityRepo

    @Inject lateinit var settingsStore: SettingsStore

    @Inject lateinit var pubkyRepo: PubkyRepo

    @Inject lateinit var contactPaymentSettingsRepo: ContactPaymentSettingsRepo

    @Inject lateinit var publicPaykitRepo: PublicPaykitRepo

    @Inject lateinit var privatePaykitRepo: PrivatePaykitRepo

    @Inject lateinit var widgetsStore: WidgetsStore

    @Inject lateinit var widgetsRepo: WidgetsRepo

    @Inject lateinit var vssStoreIdProvider: VssStoreIdProvider

    @Test
    fun restoredOriginalObservationNavigatesWithoutRetryOrNativeSend() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Env.initAppStoragePath(context.filesDir.absolutePath)
        hiltRule.inject()
        val original = requireNotNull(store.current())
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedBackendTxid"))
        assertEquals(expected, original.txid)
        assertTrue(original.hasPositiveEvidence && original.localFollowupComplete)
        assertTrue(original.requestId == null && !original.isTransfer)
        assertTrue(!lightningRepo.lightningState.value.nodeLifecycleState.isRunning())
        val binding = vssStoreIdProvider.getBackupWalletBinding(original.walletIndex)
        val wire = ActiveOnchainAttemptBackup.from(original, Env.network.name.lowercase(), binding)
        val restored = wire.restored(Env.network.name.lowercase(), binding, original.walletId, original.walletIndex)
        assertTrue(
            runCatching {
                wire.restored(Env.network.name.lowercase(), "22".repeat(32), original.walletId, original.walletIndex)
            }.isFailure,
        )
        try {
            store.restoreActive(restored.copy(evidence = OnchainSendEvidence.Unknown))
            assertEquals(original.originalInputs, store.current()?.originalInputs)
            assertEquals(original.candidateTxids, store.current()?.candidateTxids)
            val vm = SendPendingViewModel(PendingPaymentRepo(), activityRepo, lightningRepo)
            val settings = SettingsViewModel(
                context,
                settingsStore,
                pubkyRepo,
                contactPaymentSettingsRepo,
                publicPaykitRepo,
                privatePaykitRepo,
                widgetsStore,
                widgetsRepo,
            )
            var recovered by mutableStateOf<Pair<String, Long>?>(null)
            composeTestRule.setContent {
                AppThemeSurface {
                    CompositionLocalProvider(LocalSettingsViewModel provides settings) {
                        if (recovered == null) {
                            SendPendingScreen(
                                paymentHash = expected,
                                amount = 1L, // Production VM must restore the original receipt's amount.
                                observeResolution = false,
                                isOnchain = true,
                                onPaymentSuccess = { _, _ -> error("Lightning route must not complete") },
                                onPaymentError = { error("No payment failed") },
                                onClose = {},
                                onViewDetails = {},
                                viewModel = vm,
                                retryOriginal = { _, _ -> error("Observation must not invoke retry") },
                                onRecovered = { txid, amount -> recovered = txid to amount },
                                savedStateHandle = SavedStateHandle(),
                                onNavigateToPin = { error("Observation must not request spend authentication") },
                            )
                        } else {
                            Text("Original payment recovered")
                        }
                    }
                }
            }
            composeTestRule.waitUntil(10_000) { vm.uiState.value.recoveryAttempt != null }
            composeTestRule.onNodeWithText("Payment Pending").assertIsDisplayed()
            screenshot(context, "current-pending-observation-integration.png")
            // The runner independently checked this exact txid, inputs and amount. This injects the local seam;
            // it does not claim a native event, broadcast fault, new acceptance or full application navigation.
            val observed = assertNotNull(store.observeExactTransaction(expected))
            store.markLocalFollowupComplete(observed.attemptId, observed.walletIndex)
            composeTestRule.waitUntil(10_000) { recovered != null }
            assertEquals(expected to original.amountSats.toLong(), recovered)
            composeTestRule.onNodeWithText("Original payment recovered").assertIsDisplayed()
            screenshot(context, "current-recovered-observation-integration.png")
            File(context.getExternalFilesDir(null), "current-observation-integration.json")
                .writeText(Json.encodeToString(requireNotNull(store.current())))
        } finally {
            // Preserve the owned fixture's original known-positive operation and completion flag.
            store.observeExactTransaction(expected)
            store.markLocalFollowupComplete(original.attemptId, original.walletIndex)
            store.restoreActive(original)
            store.markLocalFollowupComplete(original.attemptId, original.walletIndex)
        }
        assertEquals(original, store.current())
    }

    private fun screenshot(context: Context, name: String) {
        val destination = File(context.getExternalFilesDir(null), name)
        Thread.sleep(500)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        destination.outputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }
}
