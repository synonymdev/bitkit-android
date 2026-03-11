package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.PubkyStoreData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object PubkyStoreSerializer : Serializer<PubkyStoreData> {
    override val defaultValue: PubkyStoreData = PubkyStoreData()

    override suspend fun readFrom(input: InputStream): PubkyStoreData {
        return try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Failed to deserialize PubkyStoreData: $e")
            defaultValue
        }
    }

    override suspend fun writeTo(t: PubkyStoreData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
