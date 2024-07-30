package to.bitkit.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config")
data class ConfigEntity(
    @PrimaryKey val walletIndex: Long = 0,
)
