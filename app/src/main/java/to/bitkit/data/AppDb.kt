package to.bitkit.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import to.bitkit.BuildConfig
import to.bitkit.Env
import to.bitkit.data.entities.ConfigEntity

@Database(
    entities = [
        ConfigEntity::class,
    ],
    version = 1,
)
abstract class AppDb : RoomDatabase() {
    abstract fun configDao(): ConfigDao

    companion object {
        private val NAME = "${BuildConfig.APPLICATION_ID}.${Env.network.id}.sqlite"

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
            return Room.databaseBuilder(context, AppDb::class.java, NAME)
                .setJournalMode(JournalMode.TRUNCATE)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        val request = OneTimeWorkRequestBuilder<SeedDbWorker>().build()
                        WorkManager.getInstance(context).enqueue(request)
                    }
                })
                .build()
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

internal class SeedDbWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = coroutineScope {
        try {
            val db = AppDb.getInstance(applicationContext)
            db.configDao().upsert(
                ConfigEntity(
                    walletIndex = 0L,
                ),
            )
            Result.success()
        } catch (ex: Exception) {
            Result.failure()
        }
    }
}
