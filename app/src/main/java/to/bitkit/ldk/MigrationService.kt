package to.bitkit.ldk

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ldk.structs.KeysManager
import to.bitkit.Env
import to.bitkit.Tag.LDK
import to.bitkit.ext.hex
import java.io.File
import javax.inject.Inject
import kotlin.io.path.Path
import org.ldk.structs.Result_C2Tuple_ThirtyTwoBytesChannelMonitorZDecodeErrorZ.Result_C2Tuple_ThirtyTwoBytesChannelMonitorZDecodeErrorZ_OK as ChannelMonitorDecodeResultTuple
import org.ldk.structs.UtilMethods.C2Tuple_ThirtyTwoBytesChannelMonitorZ_read as read32BytesChannelMonitor

class MigrationService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun migrate(seed: ByteArray, manager: ByteArray, monitors: List<ByteArray>) {
        Log.d(LDK, "Migrating LDK backup…")

        val file = Path(Env.Storage.ldk, LDK_DB_NAME).toFile()

        // Skip if db already exists
        if (file.exists()) {
            Log.d(LDK, "Migration skipped: ldk-node db exists at: $file")
            return
        }

        val path = file.path
        Log.d(LDK, "Creating ldk-node db at: $path")
        Log.d(LDK, "Seeding ldk-node db with LDK backup data…")

        LdkNodeDataDbHelper(context, path).writableDatabase.use {
            it.beginTransaction()
            try {
                it.insertManager(manager)
                it.insertMonitors(seed, monitors)

                it.execSQL("DROP TABLE IF EXISTS android_metadata")
                it.setTransactionSuccessful()
            } finally {
                it.endTransaction()
            }
        }

        File("$path-journal").delete()

        Log.i(LDK, "Migrated LDK backup to ldk-node db at: $path")
    }

    private fun SQLiteDatabase.insertManager(manager: ByteArray) {
        val values = ContentValues().apply {
            put(PRIMARY_NAMESPACE, "")
            put(SECONDARY_NAMESPACE, "")
            put(KEY, "manager")
            put(VALUE, manager)
        }
        insert(LDK_NODE_DATA, null, values)
    }

    private fun SQLiteDatabase.insertMonitors(seed: ByteArray, monitors: List<ByteArray>) {
        val seconds = System.currentTimeMillis() / 1000L
        val nanoSeconds = (seconds * 1000 * 1000).toInt()

        val keysManager = KeysManager.of(seed, seconds, nanoSeconds)
        val (entropySource, signerProvider) = keysManager.as_EntropySource() to keysManager.as_SignerProvider()

        for (monitor in monitors) {
            val channelMonitor = read32BytesChannelMonitor(monitor, entropySource, signerProvider).takeIf { it.is_ok }
                ?.let { it as? ChannelMonitorDecodeResultTuple }?.res?._b
                ?: throw Error("Could not read channel monitor using read32BytesChannelMonitor")
            val fundingTx = channelMonitor._funding_txo._a._txid?.reversedArray()?.hex
                ?: throw Error("Could not read txid from funding tx OutPoint of channel monitor")
            val index = channelMonitor._funding_txo._a._index
            val key = "${fundingTx}_$index"

            val values = ContentValues().apply {
                put(PRIMARY_NAMESPACE, "monitors")
                put(SECONDARY_NAMESPACE, "")
                put(KEY, key)
                put(VALUE, monitor)
            }
            insert(LDK_NODE_DATA, null, values)

            Log.d(LDK, "Inserted monitor: $key")
        }
    }

    companion object {
        private const val LDK_NODE_DATA = "ldk_node_data"
        private const val PRIMARY_NAMESPACE = "primary_namespace"
        private const val SECONDARY_NAMESPACE = "secondary_namespace"
        private const val KEY = "key"
        private const val VALUE = "value"
        private const val LDK_DB_NAME = "$LDK_NODE_DATA.sqlite"
    }
}

private class LdkNodeDataDbHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        val query = """
            |CREATE TABLE ldk_node_data (
            |    primary_namespace TEXT NOT NULL,
            |    secondary_namespace TEXT DEFAULT "" NOT NULL,
            |    `key` TEXT NOT NULL CHECK (`key` <> ''),
            |    value BLOB,
            |    PRIMARY KEY (primary_namespace, secondary_namespace, `key`)
            |);
            """.trimMargin()
        db.execSQL(query)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val VERSION = 2
    }
}
