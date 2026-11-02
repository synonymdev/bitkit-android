package to.bitkit.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.create
import to.bitkit.repositories.ActivityRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationServiceTest : BaseUnitTest() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `wipe removes the legacy seed, app state and the channel files a node start took`() = test {
        withLegacyAppData { service, store, keyStore ->
            store.edit { it[stringPreferencesKey(MigrationService.RN_MIGRATION_COMPLETED_KEY)] = "true" }
            service.migrateLdkData().getOrThrow()
            service.consumePendingChannelMigration()

            assertTrue(service.removeLegacyAppData().isSuccess)

            listOf("wallet0", "wallet0passphrase", "pin").forEach { verify(keyStore).deleteEntry(it) }
            assertFalse(rnKeychainFile.exists())
            assertFalse(rnMmkvDir.exists())
            assertFalse(rnLdkDir.exists())
            assertNull(store.data.first()[stringPreferencesKey("rnLocalChannelDataConsumed")])
        }
    }

    @Test
    fun `wipe fails while the legacy keystore keys remain, and still removes the app state`() = test {
        withLegacyAppData { service, _, keyStore ->
            whenever(keyStore.deleteEntry(any())).thenThrow(KeyStoreException("keystore unavailable"))

            val result = service.removeLegacyAppData()

            assertTrue(result.isFailure)
            assertFalse(rnMmkvDir.exists())
        }
    }

    @Test
    fun `wipe fails while the legacy keychain file remains`() = test {
        withLegacyAppData { service, _, _ ->
            // A read-only directory keeps its entries
            val keychainDir = requireNotNull(rnKeychainFile.parentFile)
            assertTrue(keychainDir.setWritable(false))

            val result = try {
                service.removeLegacyAppData()
            } finally {
                keychainDir.setWritable(true)
            }

            assertTrue(result.isFailure)
            assertTrue(rnKeychainFile.exists())
        }
    }

    @Test
    fun `wipe keeps the legacy channel files that no node start took`() = test {
        withLegacyAppData { service, store, keyStore ->
            // The migration completed in an earlier process, or failed to load its channel files, and left no evidence
            store.edit { it[stringPreferencesKey(MigrationService.RN_MIGRATION_COMPLETED_KEY)] = "true" }

            service.removeLegacyAppData()

            verify(keyStore).deleteEntry("wallet0")
            assertFalse(rnKeychainFile.exists())
            assertFalse(rnMmkvDir.exists())
            assertTrue(rnLdkDir.exists())

            // Loaded, but the node start that takes them has not run
            service.migrateLdkData().getOrThrow()
            service.removeLegacyAppData()
            assertTrue(rnLdkDir.exists())
        }
    }

    @Test
    fun `missing transfer markers remain pending until their activity exists`() = test {
        val activityRepo = mock<ActivityRepo>()
        val service = createService(activityRepo)
        val markers = mapOf("transfer" to "channel")

        val pending = service.applyRemoteTransfers(markers)
        assertEquals(markers, pending)
        verify(activityRepo, never()).updateActivity(any(), any(), any())

        val activity = onchain("transfer")
        whenever(activityRepo.getOnchainActivityByTxId("transfer")).thenReturn(activity)
        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.success(Unit))
        assertTrue(service.applyRemoteTransfers(pending).isEmpty())
        verify(activityRepo).updateActivity(
            "transfer",
            Activity.Onchain(activity.copy(isTransfer = true, channelId = "channel")),
        )
    }

    @Test
    fun `missing boost markers remain pending until their activities exist`() = test {
        val activityRepo = mock<ActivityRepo>()
        val service = createService(activityRepo)
        val markers = mapOf("parent" to "child")
        val pending = service.applyBoostTransactions(markers)
        assertEquals(markers, pending)

        whenever(activityRepo.getOnchainActivityByTxId("parent")).thenReturn(onchain("parent"))
        whenever(activityRepo.getOnchainActivityByTxId("child")).thenReturn(onchain("child"))
        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.success(Unit))
        assertTrue(service.applyBoostTransactions(pending).isEmpty())
    }

    @Test
    fun `failed transfer and boost writes remain pending`() = test {
        val activityRepo = mock<ActivityRepo>()
        val service = createService(activityRepo)
        whenever(activityRepo.getOnchainActivityByTxId(any(), any())).thenReturn(onchain("activity"))
        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.failure(AppError("write failed")))

        val transfer = mapOf("transfer" to "channel")
        val boost = mapOf("parent" to "child")
        assertEquals(transfer, service.applyRemoteTransfers(transfer))
        assertEquals(boost, service.applyBoostTransactions(boost))
    }

    private fun createService(activityRepo: ActivityRepo): MigrationService {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        return MigrationService(context, mock(), mock(), mock(), mock(), activityRepo, mock(), mock(), mock(), mock())
    }

    private fun onchain(id: String) = OnchainActivity.create(
        walletId = "wallet0",
        id = id,
        txType = PaymentType.SENT,
        txId = id,
        value = 1000uL,
        fee = 100uL,
        feeRate = 1uL,
        address = "address",
        timestamp = 1uL,
    )

    @Test
    fun `fetchOrdersInChunks should split ids into chunks and concatenate results`() = runTest {
        val orderIds = (1..25).map { "order$it" }
        val requests = mutableListOf<List<String>>()

        val result = fetchOrdersInChunks(orderIds) { ids, _ ->
            requests += ids
            ids.map { orderId -> mock<IBtOrder> { on { id } doReturn orderId } }
        }

        assertEquals(listOf(20, 5), requests.map { it.size })
        assertEquals(orderIds, requests.flatten())
        assertEquals(orderIds, result.map { it.id })
    }

    @Test
    fun `fetchOrdersInChunks should refresh active orders only on the first chunk`() = runTest {
        val orderIds = (1..45).map { "order$it" }
        val refreshFlags = mutableListOf<Boolean>()

        fetchOrdersInChunks(orderIds) { _, refreshActive ->
            refreshFlags += refreshActive
            emptyList()
        }

        assertEquals(listOf(true, false, false), refreshFlags)
    }

    @Test
    fun `fetchOrdersInChunks should not fetch when ids are empty`() = runTest {
        val requests = mutableListOf<List<String>>()

        val result = fetchOrdersInChunks(emptyList()) { ids, _ ->
            requests += ids
            emptyList()
        }

        assertTrue(requests.isEmpty())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `unapplied metadata keeps only tags whose activity is missing`() {
        val metadata = RNMetadata(
            tags = mapOf("known" to listOf("sent"), "missing" to listOf("received")),
            lastUsedTags = listOf("sent"),
        )

        val remaining = unappliedRnMetadata(metadata, setOf("missing"))

        assertEquals(mapOf("missing" to listOf("received")), remaining?.tags)
        assertEquals(null, remaining?.lastUsedTags)
    }

    @Test
    fun `unapplied metadata is dropped when every tag was applied`() {
        val metadata = RNMetadata(tags = mapOf("known" to listOf("sent")), lastUsedTags = listOf("sent"))

        assertEquals(null, unappliedRnMetadata(metadata, emptySet()))
    }

    @Test
    fun `unapplied metadata is dropped when there are no tags`() {
        val metadata = RNMetadata(tags = null, lastUsedTags = listOf("sent"))

        assertEquals(null, unappliedRnMetadata(metadata, setOf("missing")))
    }

    private val rnKeychainFile get() = File(temporaryFolder.root, "datastore/RN_KEYCHAIN.preferences_pb")
    private val rnMmkvDir get() = File(temporaryFolder.root, "mmkv")
    private val rnLdkDir get() = File(temporaryFolder.root, "ldk")

    private suspend fun withLegacyAppData(
        block: suspend (MigrationService, DataStore<Preferences>, KeyStore) -> Unit,
    ) {
        rnKeychainFile.apply { parentFile?.mkdirs() }.writeText("ciphertext")
        File(rnMmkvDir, "mmkv.default").apply { parentFile?.mkdirs() }.writeText("state")
        File(rnLdkDir, "wallet0bitcoinRegtestldkaccountv3/channel_manager.bin").apply { parentFile?.mkdirs() }
            .writeText("manager")
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(temporaryFolder.root)
        val keyStore = mock<KeyStore>()
        whenever(keyStore.containsAlias(any())).thenReturn(true)
        val storeFile = File(temporaryFolder.root, "rn_migration.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { storeFile }
        val service = MigrationService(
            context, mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), store,
            androidKeyStore = { keyStore },
        )
        try {
            block(service, store, keyStore)
        } finally {
            job.cancelAndJoin()
        }
    }
}
