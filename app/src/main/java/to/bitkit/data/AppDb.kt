package to.bitkit.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.lightningdevkit.ldknode.Network
import to.bitkit.BuildConfig
import to.bitkit.data.dao.InvoiceTagDao
import to.bitkit.data.entities.ConfigEntity
import to.bitkit.data.entities.InvoiceTagEntity
import to.bitkit.data.typeConverters.StringListConverter

@Database(
    entities = [
        ConfigEntity::class,
        InvoiceTagEntity::class,
    ],
    version = 2,
)

@TypeConverters(StringListConverter::class)
abstract class AppDb : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun invoiceTagDao(): InvoiceTagDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        @Volatile
        private var currentNetwork: Network? = null

        fun getInstance(context: Context, settingsStore: SettingsStore): AppDb {
            val selectedNetwork = runBlocking { settingsStore.data.first() }.selectedNetwork

            // If network changed, clear the instance to force recreation
            if (currentNetwork != selectedNetwork) {
                synchronized(this) {
                    instance?.close()
                    instance = null
                    currentNetwork = selectedNetwork
                }
            }

            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context, selectedNetwork).also { newInstance ->
                    instance = newInstance
                }
            }
        }

        private fun buildDatabase(context: Context, network: Network): AppDb {
            val dbName = "${BuildConfig.APPLICATION_ID}.${network.name.lowercase()}.sqlite"

            return Room.databaseBuilder(context, AppDb::class.java, dbName)
                .setJournalMode(JournalMode.TRUNCATE)
                .fallbackToDestructiveMigration() // TODO remove in prod
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        val request = OneTimeWorkRequestBuilder<SeedDbWorker>().build()
                        try {
                            WorkManager.getInstance(context).enqueue(request)
                        } catch (_: Exception) {
                            // ignore - occurs in ui tests
                        }
                    }
                })
                .build()
        }
    }
}

@HiltWorker
class SeedDbWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsStore: SettingsStore,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = coroutineScope {
        try {
            // Note: This worker doesn't have access to DI
            val db = AppDb.getInstance(applicationContext, settingsStore)
            db.configDao().upsert(
                ConfigEntity(
                    walletIndex = 0L,
                ),
            )
            Result.success()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM config")
    fun getAll(): Flow<List<ConfigEntity>>

    @Upsert
    suspend fun upsert(vararg entities: ConfigEntity)
}
