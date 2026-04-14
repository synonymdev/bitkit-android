package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import to.bitkit.data.MilestoneStoreData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object MilestoneStoreSerializer : Serializer<MilestoneStoreData> {
    private const val TAG = "MilestoneStoreSerializer"

    override val defaultValue: MilestoneStoreData = MilestoneStoreData()

    override suspend fun readFrom(input: InputStream): MilestoneStoreData {
        return runCatching {
            json.decodeFromString<MilestoneStoreData>(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize MilestoneStoreData", it, context = TAG)
            defaultValue
        }
    }

    override suspend fun writeTo(t: MilestoneStoreData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
