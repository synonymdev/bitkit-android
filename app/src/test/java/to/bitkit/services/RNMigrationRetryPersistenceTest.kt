package to.bitkit.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.di.json
import to.bitkit.ext.create
import to.bitkit.repositories.ActivityRepo
import to.bitkit.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RNMigrationRetryPersistenceTest : BaseUnitTest() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val metadataKey = stringPreferencesKey("rnPendingMetadata")
    private val scheduleKey = stringPreferencesKey("rnMigrationRetrySchedule")

    @Test
    fun `service saves backoff to disk and reloads it before recovering late metadata`() = test {
        val file = File(temporaryFolder.root, "rn_migration.preferences_pb")
        val activityRepo = mock<ActivityRepo>()
        val activityService = mock<ActivityService>()
        val coreService = mock<CoreService>()
        whenever(coreService.activity).thenReturn(activityService)
        whenever(activityRepo.getActivity(any(), any())).thenReturn(Result.success(null))
        val metadata = RNMetadata(tags = mapOf("late" to listOf("restored")))
        var savedSchedule: RNMigrationRetrySchedule? = null

        withStore(file) { store ->
            store.edit { it[metadataKey] = json.encodeToString(metadata) }
            val service = createService(store, activityRepo, coreService)
            assertTrue(service.shouldRetryPendingMigrationData())
            service.reapplyMetadataAfterSync()

            val preferences = store.data.first()
            assertEquals(metadata.tags, json.decodeFromString<RNMetadata>(assertNotNull(preferences[metadataKey])).tags)
            savedSchedule = json.decodeFromString<RNMigrationRetrySchedule>(assertNotNull(preferences[scheduleKey]))
            assertEquals(60_000L, savedSchedule?.delayMillis)
            assertFalse(service.canCleanupAfterMigration)
        }

        withStore(file) { store ->
            val service = createService(store, activityRepo, coreService)
            val schedule = assertNotNull(savedSchedule)
            assertFalse(service.shouldRetryPendingMigrationData(schedule.attemptedAtMillis + schedule.delayMillis - 1))
            assertTrue(service.shouldRetryPendingMigrationData(schedule.attemptedAtMillis + schedule.delayMillis))
            service.reapplyMetadataAfterSync()
            val doubled = json.decodeFromString<RNMigrationRetrySchedule>(assertNotNull(store.data.first()[scheduleKey]))
            assertEquals(120_000L, doubled.delayMillis)

            whenever(activityRepo.getOnchainActivityByTxId("late")).thenReturn(onchain("late"))
            assertTrue(service.shouldRetryPendingMigrationData(doubled.attemptedAtMillis + doubled.delayMillis))
            service.reapplyMetadataAfterSync()
            verify(activityService).upsertTags(listOf(ActivityTags("wallet0", "late", listOf("restored"))))
            assertTrue(service.canCleanupAfterMigration)
            assertNull(store.data.first()[metadataKey])
            assertNull(store.data.first()[scheduleKey])
        }

        withStore(file) { store ->
            val service = createService(store, activityRepo, coreService)
            assertFalse(service.hasPendingMigrationRetries())
            store.edit { it[metadataKey] = json.encodeToString(RNMetadata(tags = mapOf("new" to listOf("tag")))) }
            assertTrue(service.shouldRetryPendingMigrationData())
        }
    }

    @Test
    fun `cleanup removes the saved schedule and pending metadata from disk`() = test {
        val file = File(temporaryFolder.root, "cleanup.preferences_pb")
        withStore(file) { store ->
            store.edit { it[metadataKey] = json.encodeToString(RNMetadata(tags = mapOf("missing" to listOf("tag")))) }
            val activityRepo = mock<ActivityRepo>()
            whenever(activityRepo.getActivity(any(), any())).thenReturn(Result.success(null))
            val service = createService(store, activityRepo, mock())
            service.reapplyMetadataAfterSync()
            assertNotNull(store.data.first()[scheduleKey])
            service.cleanupAfterMigration()
        }
        withStore(file) { store ->
            assertNull(store.data.first()[scheduleKey])
            assertNull(store.data.first()[metadataKey])
            val service = createService(store, mock(), mock())
            assertFalse(service.hasPendingMigrationRetries())
        }
    }

    private suspend fun withStore(file: File, block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            produceFile = { file },
        )
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun createService(store: DataStore<Preferences>, activityRepo: ActivityRepo, coreService: CoreService): MigrationService {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        return MigrationService(context, mock(), mock(), mock(), mock(), activityRepo, coreService, mock(), mock(), mock(), store)
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
}
