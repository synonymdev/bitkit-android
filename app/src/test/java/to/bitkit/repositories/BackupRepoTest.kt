package to.bitkit.repositories

import android.content.Context
import com.synonym.vssclient.VssItem
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.AppDb
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.data.backup.VssBackupClient
import to.bitkit.data.backup.VssBackupClientLdk
import to.bitkit.data.dao.TransferDao
import to.bitkit.data.entities.TransferEntity
import to.bitkit.di.json
import to.bitkit.models.BackupCategory
import to.bitkit.models.PrivatePaykitContactLinkBackupV1
import to.bitkit.models.WalletBackupV1
import to.bitkit.services.LightningService
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import javax.inject.Provider
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class BackupRepoTest : BaseUnitTest() {
    private val context = mock<Context>()
    private val cacheStore = mock<CacheStore>()
    private val vssBackupClient = mock<VssBackupClient>()
    private val vssBackupClientLdk = mock<VssBackupClientLdk>()
    private val settingsStore = mock<SettingsStore>()
    private val widgetsStore = mock<WidgetsStore>()
    private val blocktankRepo = mock<BlocktankRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val privatePaykitRepo = mock<PrivatePaykitRepo>()
    private val privatePaykitAddressReservationRepo = mock<PrivatePaykitAddressReservationRepo>()
    private val preActivityMetadataRepo = mock<PreActivityMetadataRepo>()
    private val lightningService = mock<LightningService>()
    private val clock = mock<Clock>()
    private val db = mock<AppDb>()
    private val transferDao = mock<TransferDao>()
    private val settingsData = MutableStateFlow(SettingsData())

    private lateinit var sut: BackupRepo

    @Before
    fun setUp() = test {
        whenever(clock.now()).thenReturn(Instant.fromEpochMilliseconds(1_000))
        whenever(db.transferDao()).thenReturn(transferDao)
        whenever { transferDao.upsert(any<List<TransferEntity>>()) }.thenReturn(Unit)
        whenever { cacheStore.updateBackupStatus(any(), any()) }.thenReturn(Unit)
        whenever { cacheStore.update(any()) }.thenReturn(Unit)
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever { settingsStore.update(any()) }.thenReturn(Unit)
        whenever { vssBackupClient.getObject(any()) }.thenReturn(Result.success(null))
        whenever { vssBackupClient.putObject(any(), any()) }
            .thenReturn(Result.success(VssItem(key = BackupCategory.SETTINGS.name, value = byteArrayOf(), version = 1)))
        whenever { privatePaykitRepo.restoreBackup(anyOrNull()) }.thenReturn(Result.success(Unit))
        whenever { privatePaykitAddressReservationRepo.restoreBackup(any()) }.thenReturn(Result.success(Unit))
        whenever {
            privatePaykitAddressReservationRepo.reconcileReservedIndexesWithLdk()
        }.thenReturn(Result.success(Unit))

        sut = createSut()
    }

    @Test
    fun `full restore should fail when private Paykit reservations fail to restore`() = test {
        stubWalletBackup()
        whenever { privatePaykitAddressReservationRepo.restoreBackup(any()) }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(privatePaykitRepo, never()).restoreBackup(any())
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `full restore should fail when private Paykit contact links fail to restore`() = test {
        stubWalletBackup()
        whenever { privatePaykitRepo.restoreBackup(anyOrNull()) }
            .thenReturn(Result.failure(BackupRepoTestError("restore failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `full restore should fail when private Paykit reserved indexes fail to reconcile`() = test {
        stubWalletBackup()
        whenever { privatePaykitAddressReservationRepo.reconcileReservedIndexesWithLdk() }
            .thenReturn(Result.failure(BackupRepoTestError("reconcile failed")))

        val result = sut.performFullRestoreFromLatestBackup()

        assertTrue(result.isFailure)
        verify(settingsStore, never()).update(any())
    }

    private fun stubWalletBackup(
        privatePaykitContactLinks: Map<String, PrivatePaykitContactLinkBackupV1>? = null,
    ) {
        val walletBackup = WalletBackupV1(
            createdAt = 123,
            transfers = emptyList(),
            privatePaykitHighestReservedReceiveIndexByAddressType = mapOf("nativeSegwit" to 5),
            privatePaykitContactLinks = privatePaykitContactLinks,
        )
        whenever { vssBackupClient.getObject(BackupCategory.WALLET.name) }
            .thenReturn(
                Result.success(
                    VssItem(
                        key = BackupCategory.WALLET.name,
                        value = json.encodeToString(walletBackup).toByteArray(),
                        version = 1,
                    )
                )
            )
    }

    private fun createSut() = BackupRepo(
        context = context,
        ioDispatcher = testDispatcher,
        cacheStore = cacheStore,
        vssBackupClient = vssBackupClient,
        vssBackupClientLdk = vssBackupClientLdk,
        settingsStore = settingsStore,
        widgetsStore = widgetsStore,
        blocktankRepo = blocktankRepo,
        activityRepo = activityRepo,
        pubkyRepo = pubkyRepo,
        privatePaykitRepo = Provider { privatePaykitRepo },
        privatePaykitAddressReservationRepo = Provider { privatePaykitAddressReservationRepo },
        preActivityMetadataRepo = preActivityMetadataRepo,
        lightningService = lightningService,
        clock = clock,
        db = db,
    )

    private class BackupRepoTestError(message: String) : AppError(message)
}
