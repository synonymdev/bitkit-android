package to.bitkit.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ldk.structs.KeysManager
import to.bitkit.env.Env
import to.bitkit.ext.toHex
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.io.File
import javax.inject.Inject
import kotlin.io.path.Path
import org.ldk.structs.Result_C2Tuple_ThirtyTwoBytesChannelMonitorZDecodeErrorZ.Result_C2Tuple_ThirtyTwoBytesChannelMonitorZDecodeErrorZ_OK as ChannelMonitorDecodeResultTuple
import org.ldk.structs.UtilMethods.C2Tuple_ThirtyTwoBytesChannelMonitorZ_read as read32BytesChannelMonitor

class MigrationService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun migrate(seed: ByteArray, manager: ByteArray, monitors: List<ByteArray>) {
        Logger.debug("Migrating LDK backup…")

        val file = Path(Env.ldkStoragePath(0), LDK_DB_NAME).toFile()

        // Skip if db already exists
        if (file.exists()) {
            throw ServiceError.LdkNodeSqliteAlreadyExists(file.path)
        }

        val path = file.path
        Logger.debug("Creating ldk-node db at: $path")
        Logger.debug("Seeding ldk-node db with LDK backup data…")

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

        Logger.info("Migrated LDK backup to ldk-node db at: $path")
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
                ?: throw ServiceError.LdkToLdkNodeMigration
            val fundingTx = channelMonitor._funding_txo._a._txid?.reversedArray()?.toHex()
                ?: throw ServiceError.LdkToLdkNodeMigration
            val index = channelMonitor._funding_txo._a._index
            val key = "${fundingTx}_$index"

            val values = ContentValues().apply {
                put(PRIMARY_NAMESPACE, "monitors")
                put(SECONDARY_NAMESPACE, "")
                put(KEY, key)
                put(VALUE, monitor)
            }
            insert(LDK_NODE_DATA, null, values)

            Logger.debug("Inserted monitor: $key")
        }
    }

    class LdkNodeDataDbHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, LDK_DB_VERSION) {
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
    }

    companion object {
        private const val LDK_NODE_DATA = "ldk_node_data"
        private const val PRIMARY_NAMESPACE = "primary_namespace"
        private const val SECONDARY_NAMESPACE = "secondary_namespace"
        private const val KEY = "key"
        private const val VALUE = "value"
        private const val LDK_DB_NAME = "$LDK_NODE_DATA.sqlite"
        private const val LDK_DB_VERSION = 2 // Should match SCHEMA_USER_VERSION from ldk-node
    }
}
