package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.TrezorData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object TrezorDataSerializer : Serializer<TrezorData> {
    private const val TAG = "TrezorDataSerializer"

    override val defaultValue: TrezorData = TrezorData()

    override suspend fun readFrom(input: InputStream): TrezorData {
        return try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Deserialize Trezor data failed", e, context = TAG)
            defaultValue
        }
    }

    override suspend fun writeTo(t: TrezorData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
