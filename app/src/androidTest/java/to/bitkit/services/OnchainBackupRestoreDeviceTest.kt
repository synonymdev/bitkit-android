package to.bitkit.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.keychain.Keychain
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.models.WalletBackupV1
import to.bitkit.repositories.BackupRepo
import to.bitkit.repositories.OnchainSendAttemptStore
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opt-in remote backup check; run only on an owned funded test wallet. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnchainBackupRestoreDeviceTest {
    @get:Rule val hiltRule: HiltAndroidRule = HiltAndroidRule(this)
    @Inject lateinit var backups: BackupRepo
    @Inject lateinit var vss: VssBackupClient
    @Inject lateinit var attempts: OnchainSendAttemptStore
    @Inject lateinit var keychain: Keychain

    @Test
    fun remoteWalletBackupRestoresTheOriginalAcceptedPayment(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedWalletVss") == "true")
        withTimeout(180_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            Env.initAppStoragePath(context.filesDir.absolutePath)
            hiltRule.inject()
            WorkManager.initialize(context, Configuration.Builder().build())
            vss.setup().getOrThrow()
            if (backups.hasPendingWalletRestore()) {
                backups.performFullRestoreFromLatestBackup().getOrThrow()
            }
            val original = assertNotNull(attempts.current())
            assertTrue(original.hasPositiveEvidence && original.localFollowupComplete)
            assertTrue(original.requestId == null && !original.isTransfer)
            vss.setup(original.walletIndex).getOrThrow()
            try {
                keychain.upsertString(
                    Keychain.Key.ONCHAIN_SEND_ATTEMPT.name,
                    Json.encodeToString(original.copy(localFollowupComplete = false)),
                    original.walletIndex,
                )
                assertTrue(assertNotNull(attempts.current()).blocksNextSend)
                // Upload the complete owned fixture first: restoring must not rewind unrelated categories.
                for (category in BackupCategory.entries.filterNot { it == BackupCategory.LIGHTNING_CONNECTIONS }) {
                    backups.triggerBackup(category).getOrThrow()
                }
                val remote = assertNotNull(vss.getObject(BackupCategory.WALLET.name).getOrThrow())
                val payload = Json.decodeFromString<WalletBackupV1>(remote.value.decodeToString())
                val wire = assertNotNull(payload.paykitPaymentState?.activeOnchainAttempt)
                assertEquals(original.attemptId, wire.attemptId)
                assertEquals(original.txid, wire.txid)
                keychain.delete(Keychain.Key.ONCHAIN_SEND_ATTEMPT.name, original.walletIndex)
                assertNull(attempts.current())
                backups.performFullRestoreFromLatestBackup().getOrThrow()
                val restored = assertNotNull(attempts.current())
                assertEquals(original.attemptId, restored.attemptId)
                assertEquals(original.walletIndex, restored.walletIndex)
                assertEquals(original.walletId, restored.walletId)
                assertEquals(original.txid, restored.txid)
                assertEquals(original.amountSats, restored.amountSats)
                assertEquals(original.originalInputs, restored.originalInputs)
                assertEquals(original.candidateTxids, restored.candidateTxids)
                assertTrue(restored.hasPositiveEvidence && restored.localFollowupComplete)
            } finally {
                attempts.restoreActive(original)
                attempts.markLocalFollowupComplete(original.attemptId, original.walletIndex)
            }
        }
    }
}
