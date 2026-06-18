package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import to.bitkit.data.HwWalletData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object HwWalletDataSerializer : Serializer<HwWalletData> {
    private const val TAG = "HwWalletDataSerializer"

    override val defaultValue: HwWalletData = HwWalletData()

    override suspend fun readFrom(input: InputStream): HwWalletData {
        return try {
            json.decodeFromString(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            Logger.error("Deserialize hardware wallet data failed", e, context = TAG)
            defaultValue
        }
    }

    override suspend fun writeTo(t: HwWalletData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
