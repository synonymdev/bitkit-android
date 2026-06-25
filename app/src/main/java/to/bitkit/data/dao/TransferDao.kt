package to.bitkit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import to.bitkit.data.entities.TransferEntity

@Dao
@Suppress("TooManyFunctions")
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity)

    @Upsert
    suspend fun upsert(transfer: TransferEntity)

    @Upsert
    suspend fun upsert(transfers: List<TransferEntity>)

    @Update
    suspend fun update(transfer: TransferEntity)

    @Query("SELECT * FROM transfers")
    suspend fun getAll(): List<TransferEntity>

    @Query("SELECT * FROM transfers")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE isSettled = 0")
    fun getActiveTransfers(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE fundingTxId = :fundingTxId LIMIT 1")
    suspend fun getByFundingTxId(fundingTxId: String): TransferEntity?

    @Query("UPDATE transfers SET isSettled = 1, settledAt = :settledAt WHERE id = :id")
    suspend fun markSettled(id: String, settledAt: Long)

    @Query("DELETE FROM transfers WHERE isSettled = 1 AND settledAt < :expirationTimestamp")
    suspend fun deleteOldSettled(expirationTimestamp: Long)
}
