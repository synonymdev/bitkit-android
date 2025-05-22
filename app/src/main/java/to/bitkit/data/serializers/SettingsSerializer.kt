package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object SettingsSerializer : Serializer<SettingsData> {
    override val defaultValue: SettingsData = SettingsData()

    override suspend fun readFrom(input: InputStream): SettingsData {
        return try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Failed to deserialize settings: $e")
            defaultValue
        }
    }

    override suspend fun writeTo(t: SettingsData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
