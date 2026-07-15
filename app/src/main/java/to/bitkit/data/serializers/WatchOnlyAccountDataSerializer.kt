package to.bitkit.data.serializers

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.WatchOnlyAccountData
import to.bitkit.di.json
import java.io.InputStream
import java.io.OutputStream

object WatchOnlyAccountDataSerializer : Serializer<WatchOnlyAccountData> {
    override val defaultValue = WatchOnlyAccountData()

    override suspend fun readFrom(input: InputStream): WatchOnlyAccountData = try {
        json.decodeFromString(input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("Failed to deserialize watch-only account data", error)
    }

    override suspend fun writeTo(t: WatchOnlyAccountData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
