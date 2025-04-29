package to.bitkit.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoice_tag")
data class InvoiceTagEntity(
    @PrimaryKey val paymentHash: String,
    val tags: List<String>,
    val createdAt: Long
)
