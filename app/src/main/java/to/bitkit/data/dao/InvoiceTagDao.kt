package to.bitkit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import to.bitkit.data.entities.InvoiceTagEntity

@Dao
interface InvoiceTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveInvoice(invoiceTag: InvoiceTagEntity)

    @Query("SELECT * FROM invoice_tag WHERE paymentHash = :paymentHash LIMIT 1")
    suspend fun searchInvoice(paymentHash: String) : InvoiceTagEntity?

    @Delete
    suspend fun deleteInvoice(invoiceTag: InvoiceTagEntity)

    @Query("DELETE FROM invoice_tag WHERE paymentHash = :paymentHash")
    suspend fun deleteInvoiceByPaymentHash(paymentHash: String)

    @Query("DELETE FROM invoice_tag")
    suspend fun deleteAllInvoices()

    @Query("DELETE FROM invoice_tag WHERE createdAt < :expirationTimeStamp")
    suspend fun deleteExpiredInvoices(expirationTimeStamp: Long)
}
