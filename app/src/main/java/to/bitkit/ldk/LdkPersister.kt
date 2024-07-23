package to.bitkit.ldk

import android.util.Log
import org.ldk.enums.ChannelMonitorUpdateStatus
import org.ldk.structs.ChannelMonitor
import org.ldk.structs.ChannelMonitorUpdate
import org.ldk.structs.MonitorUpdateId
import org.ldk.structs.OutPoint
import org.ldk.structs.Persist
import to.bitkit._LDK
import to.bitkit.ext.toHex
import java.io.File

object LdkPersister : Persist.PersistInterface {
    private fun persist(id: OutPoint?, data: ByteArray?) {
        if (id != null && data != null) {
            persist("channels/${id.to_channel_id().toHex()}.bin", data)
        }
    }

    override fun persist_new_channel(
        id: OutPoint?,
        data: ChannelMonitor?,
        updateId: MonitorUpdateId?,
    ): ChannelMonitorUpdateStatus? {
        return try {
            if (data != null && id != null) {
                Log.d(_LDK, "persist_new_channel: ${id.to_channel_id().toHex()}")
                persist(id, data.write())
            }
            ChannelMonitorUpdateStatus.LDKChannelMonitorUpdateStatus_Completed
        } catch (e: Exception) {
            Log.d(_LDK, "Failed to write to file: ${e.message}")
            ChannelMonitorUpdateStatus.LDKChannelMonitorUpdateStatus_UnrecoverableError
        }
    }

    override fun update_persisted_channel(
        id: OutPoint?,
        update: ChannelMonitorUpdate?,
        data: ChannelMonitor?,
        updateId: MonitorUpdateId,
    ): ChannelMonitorUpdateStatus? {
        // Consider returning ChannelMonitorUpdateStatus_InProgress for async backups
        return try {
            if (data != null && id != null) {
                Log.d(_LDK, "update_persisted_channel: ${id.to_channel_id().toHex()}")
                persist(id, data.write())
            }
            ChannelMonitorUpdateStatus.LDKChannelMonitorUpdateStatus_Completed
        } catch (e: Exception) {
            Log.d(_LDK, "Failed to write to file: ${e.message}")
            ChannelMonitorUpdateStatus.LDKChannelMonitorUpdateStatus_UnrecoverableError
        }
    }
}

fun persist(to: String, data: ByteArray?) {
    val fileName = "$ldkDir/$to"
    val file = File(fileName)
    if (data != null) {
        Log.d(_LDK, "Writing to file: $fileName")
        file.writeBytes(data)
    }
}

class JsonBuilder {
    private var json: String = ""

    fun put(key: String, value: String?): JsonBuilder {
        if (json.isNotEmpty()) json += ','
        json += "\"$key\":\"$value\""
        return this
    }

    override fun toString(): String {
        return "{$json}"
    }

    fun persist(to: String) {
        val dir = File(to)
        if (!dir.exists()) {
            dir.mkdir()
        }

        File("$to/${System.currentTimeMillis()}.json")
            .writeText(this.toString())
    }
}