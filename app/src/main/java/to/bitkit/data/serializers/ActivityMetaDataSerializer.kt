package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.dto.ActivityMetaData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object ActivityMetaDataSerializer : Serializer<ActivityMetaData> {
    override val defaultValue: ActivityMetaData = ActivityMetaData.Bolt11(
        paymentId = "",
        invoice = ""
    )

    override suspend fun readFrom(input: InputStream): ActivityMetaData {
        return try {
            json.decodeFromString<ActivityMetaData>(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Failed to deserialize ActivityMetaData: $e")
            defaultValue
        }
    }

    override suspend fun writeTo(t: ActivityMetaData, output: OutputStream) {
        output.write(json.encodeToString<ActivityMetaData>(t).encodeToByteArray())
    }
}
