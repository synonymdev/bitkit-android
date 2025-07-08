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
        private const val DB_NAME = "${BuildConfig.APPLICATION_ID}.sqlite"

        @Volatile
        private var instance: AppDb? = null

        fun getInstance(context: Context): AppDb {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also {
                    instance = it
                }
            }
        }

        private fun buildDatabase(context: Context): AppDb {
            return Room.databaseBuilder(context, AppDb::class.java, DB_NAME)
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
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = coroutineScope {
        try {
            val db = AppDb.getInstance(applicationContext)
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
