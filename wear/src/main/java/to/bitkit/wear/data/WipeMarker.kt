package to.bitkit.wear.data

import to.bitkit.wear.utils.AppError
import to.bitkit.wear.utils.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The note that a wallet wipe started and did not finish: an empty file whose existence is the note. It lives in
 * the seed's no-backup directory, apart from the settings file, so a settings file that is reset as corrupted
 * neither loses a wipe under way nor starts one. The file and its directory are synced, so the note survives a
 * power cut. A new file that cannot be synced is deleted again, so a note that failed to be written never starts a
 * wipe. The directory is resolved on first use, off the main thread.
 */
class WipeMarker(
    directory: Lazy<File>,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    companion object {
        private const val TAG = "WipeMarker"
        private const val FILE_NAME = "wipe.pending"
    }

    private val directory by directory
    private val file get() = File(directory, FILE_NAME)

    fun exists(): Boolean = file.exists()

    fun create() {
        directory.mkdirs()
        val isNew = !file.exists()
        runCatching { FileOutputStream(file).use(sync) }.onFailure {
            if (isNew && file.delete()) syncDirectory()
            throw it
        }
        syncDirectory()
    }

    fun clear() {
        if (!file.delete() && file.exists()) throw WipeMarkerError()
        if (directory.exists()) syncDirectory()
    }

    private fun syncDirectory() {
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            .onFailure { Logger.warn("Failed to sync the wipe marker's directory", it, context = TAG) }
    }
}

class WipeMarkerError : AppError("Failed to delete the wipe marker")
