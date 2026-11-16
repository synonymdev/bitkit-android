package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import to.bitkit.data.WakeStoreData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object WakeStoreSerializer : Serializer<WakeStoreData> {
    private const val TAG = "WakeStoreSerializer"

    override val defaultValue: WakeStoreData = WakeStoreData()

    override suspend fun readFrom(input: InputStream): WakeStoreData {
        return runCatching {
            json.decodeFromString<WakeStoreData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize WakeStoreData", it, context = TAG)
            defaultValue
        }
    }

    override suspend fun writeTo(t: WakeStoreData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
