package to.bitkit.data.serializers

import androidx.datastore.core.Serializer
import to.bitkit.appwidget.model.AppWidgetData
import to.bitkit.di.json
import to.bitkit.utils.Logger
import java.io.InputStream
import java.io.OutputStream

object AppWidgetDataSerializer : Serializer<AppWidgetData> {
    override val defaultValue: AppWidgetData = AppWidgetData()

    override suspend fun readFrom(input: InputStream): AppWidgetData {
        return runCatching<AppWidgetData> {
            json.decodeFromString(input.readBytes().decodeToString())
        }.getOrElse {
            Logger.error("Failed to deserialize", it)
            defaultValue
        }
    }

    override suspend fun writeTo(t: AppWidgetData, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
