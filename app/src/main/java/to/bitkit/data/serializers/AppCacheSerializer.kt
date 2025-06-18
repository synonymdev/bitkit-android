package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.AppCacheData
import to.bitkit.data.SettingsData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object AppCacheSerializer : Serializer<AppCacheData> {
    override val defaultValue: AppCacheData = AppCacheData()

    override suspend fun readFrom(input: InputStream): AppCacheData {
        return try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Failed to deserialize: $e")
            defaultValue
        }
    }

    override suspend fun writeTo(t: AppCacheData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
