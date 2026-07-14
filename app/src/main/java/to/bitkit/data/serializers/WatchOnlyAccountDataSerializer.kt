package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object WatchOnlyAccountDataSerializer : Serializer<WatchOnlyAccountData> {
    private const val TAG = "WatchOnlyAccountDataSerializer"

    override val defaultValue = WatchOnlyAccountData()

    override suspend fun readFrom(input: InputStream): WatchOnlyAccountData = try {
        json.decodeFromString(input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        Logger.error("Deserialize watch-only account data failed", error, context = TAG)
        defaultValue
    }

    override suspend fun writeTo(t: WatchOnlyAccountData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
