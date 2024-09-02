package to.bitkit.ext

import androidx.compose.runtime.snapshots.SnapshotStateList

fun <T> SnapshotStateList<T>.syncTo(list: List<T>): SnapshotStateList<T> {
    clear()
    this += list
    return this
}
