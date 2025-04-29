package to.bitkit.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = "invoice_tag")
data class InvoiceTagEntity(
    @PrimaryKey val paymentHash: String,
    val tags: List<String>,
    val createdAt: Long
)

class StringListConverter {
    @TypeConverter
    fun fromString(value: String): List<String> {
        return value.split(",").map { it.trim() }
    }

    @TypeConverter
    fun fromList(list: List<String>): String {
        return list.joinToString(",")
    }
}
