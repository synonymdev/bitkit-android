package to.bitkit.utils

import kotlin.math.roundToInt

object BlockTimeHelpers {
    private const val BLOCK_TIME_MINUTES = 10

    fun getDurationForBlocks(blocks: Int): String = when {
        blocks > 143 -> "${(blocks * BLOCK_TIME_MINUTES / 60.0 / 24.0).roundToInt()}d"
        blocks > 6 -> "${(blocks * BLOCK_TIME_MINUTES / 60.0).roundToInt()}h"
        else -> "${blocks * BLOCK_TIME_MINUTES}m"
    }

    fun blocksRemaining(targetHeight: UInt, currentHeight: UInt): Int =
        maxOf(0, (targetHeight.toInt() - currentHeight.toInt()))
}