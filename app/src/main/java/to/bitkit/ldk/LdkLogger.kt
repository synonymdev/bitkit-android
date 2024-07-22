package to.bitkit.ldk

import android.util.Log
import org.ldk.structs.Logger
import org.ldk.structs.Record
import to.bitkit._LDK
import java.io.File

object LdkLogger : Logger.LoggerInterface {
    override fun log(record: Record?) {
        val rawLog = record?._args.toString()
        val file = File("$ldkDir/logs.txt")

        try {
            if (!file.exists()) {
                file.createNewFile()
            }
            file.appendText("$rawLog\n")
        } catch (e: Exception) {
            Log.d(_LDK, "LdkLogger error: ${e.message}")
        }
    }
}