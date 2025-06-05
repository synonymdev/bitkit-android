package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.SettingsData
import to.bitkit.data.WidgetsData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object WidgetsSerializer : Serializer<WidgetsData> {
    override val defaultValue: WidgetsData = WidgetsData()

    override suspend fun readFrom(input: InputStream): WidgetsData {
        return try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Failed to deserialize: $e")
            defaultValue
        }
    }

    override suspend fun writeTo(t: WidgetsData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
