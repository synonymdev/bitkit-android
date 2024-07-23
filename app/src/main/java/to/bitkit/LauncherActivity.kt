package to.bitkit

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import to.bitkit.bdk.Bdk
import to.bitkit.ldk.Ldk
import to.bitkit.ldk.init
import to.bitkit.ldk.ldkDir
import to.bitkit.ui.MainActivity
import java.io.File

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        warmupNode(filesDir.absolutePath)
        startActivity(Intent(this, MainActivity::class.java))
    }
}

internal fun warmupNode(absolutePath: String): Boolean {
    initDataDir(absolutePath)

    val latestBlockHeight = Bdk.getHeight()
    val latestBlockHash = Bdk.getBlockHash(latestBlockHeight)

    val channelManagerFile = File("$ldkDir/channel-manager.bin")
    val serializedChannelManager = channelManagerFile
        .takeIf { it.exists() }
        ?.absoluteFile?.readBytes()

    val serializedChannelMonitors = readChannelMonitorFromDisk()

    return Ldk.init(
        Bdk.getLdkEntropy(),
        latestBlockHeight.toInt(),
        latestBlockHash,
        serializedChannelManager,
        serializedChannelMonitors,
    )
}

private fun initDataDir(absolutePath: String) {
    ldkDir = "$absolutePath/bitkit"
    val dir = File(ldkDir)
    if (!dir.exists()) {
        dir.mkdir()
    }

    // Initialize the LDK data directory if necessary.
    ldkDir += "/ldk-data"
    val ldkDirPath = File(ldkDir)
    if (!ldkDirPath.exists()) {
        ldkDirPath.mkdir()
        Log.d(_LDK, "Ldk dir: $ldkDirPath")
    }
}

private fun readChannelMonitorFromDisk(): Array<ByteArray> {
    val channelMonitorDirectory = File("$ldkDir/channels/")
    if (channelMonitorDirectory.isDirectory) {
        val files = channelMonitorDirectory.list()
        if (files.isNullOrEmpty()) {
            return emptyArray()
        }

        val channelMonitorList = mutableListOf<ByteArray>()
        files.forEach {
            channelMonitorList.add(File("${channelMonitorDirectory}/${it}").readBytes())
        }
        return channelMonitorList.toTypedArray()
    }

    channelMonitorDirectory.mkdir()
    Log.d(_LDK, "New channels dir: $channelMonitorDirectory")
    return emptyArray()
}
