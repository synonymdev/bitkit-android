package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import to.bitkit.data.PubkyStoreData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object PubkyStoreSerializer : Serializer<PubkyStoreData> {
    private const val TAG = "PubkyStoreSerializer"

    override val defaultValue: PubkyStoreData = PubkyStoreData()

    override suspend fun readFrom(input: InputStream): PubkyStoreData {
        return runCatching {
            json.decodeFromString<PubkyStoreData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize PubkyStoreData", it, context = TAG)
            defaultValue
        }
    }

    override suspend fun writeTo(t: PubkyStoreData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
