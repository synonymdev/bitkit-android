package to.bitkit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import to.bitkit.data.entities.TagMetadataEntity

@Dao
interface TagMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTagMetadata(tagMetadata: TagMetadataEntity)

    @Query("SELECT * FROM tag_metadata")
    suspend fun getAll(): List<TagMetadataEntity>

    // Search by payment hash (for invoices)
    @Query("SELECT * FROM tag_metadata WHERE paymentHash = :paymentHash LIMIT 1")
    suspend fun searchByPaymentHash(paymentHash: String): TagMetadataEntity?

    // Search by transaction ID
    @Query("SELECT * FROM tag_metadata WHERE txId = :txId LIMIT 1")
    suspend fun searchByTxId(txId: String): TagMetadataEntity?

    // Search by address
    @Query("SELECT * FROM tag_metadata WHERE address = :address ORDER BY createdAt DESC LIMIT 1")
    suspend fun searchByAddress(address: String): TagMetadataEntity?

    // Search by primary key (id)
    @Query("SELECT * FROM tag_metadata WHERE id = :id LIMIT 1")
    suspend fun searchById(id: String): TagMetadataEntity?

    // Get all receive transactions
    @Query("SELECT * FROM tag_metadata WHERE isReceive = 1")
    suspend fun getAllReceiveTransactions(): List<TagMetadataEntity>

    // Get all send transactions
    @Query("SELECT * FROM tag_metadata WHERE isReceive = 0")
    suspend fun getAllSendTransactions(): List<TagMetadataEntity>

    @Delete
    suspend fun deleteTagMetadata(tagMetadata: TagMetadataEntity)

    @Query("DELETE FROM tag_metadata WHERE paymentHash = :paymentHash")
    suspend fun deleteByPaymentHash(paymentHash: String)

    @Query("DELETE FROM tag_metadata WHERE txId = :txId")
    suspend fun deleteByTxId(txId: String)

    @Query("DELETE FROM tag_metadata WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tag_metadata")
    suspend fun deleteAll()

    @Query("DELETE FROM tag_metadata WHERE createdAt < :expirationTimeStamp")
    suspend fun deleteExpired(expirationTimeStamp: Long)
}
