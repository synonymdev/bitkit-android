package to.bitkit.utils

import kotlin.math.roundToInt

@Suppress("MagicNumber")
object BlockTimeHelpers {
    private const val BLOCK_TIME_MINUTES = 10
    private const val MINUTES_PER_HOUR = 60.0
    private const val HOURS_PER_DAY = 24.0
    private const val BLOCKS_PER_DAY = 143
    private const val REORG_PROTECTION_BLOCKS = 6

    fun getDurationForBlocks(blocks: Int): String = when {
        blocks > BLOCKS_PER_DAY -> "${(blocks * BLOCK_TIME_MINUTES / MINUTES_PER_HOUR / HOURS_PER_DAY).roundToInt()}d"
        blocks > REORG_PROTECTION_BLOCKS -> "${(blocks * BLOCK_TIME_MINUTES / MINUTES_PER_HOUR).roundToInt()}h"
        else -> "${blocks * BLOCK_TIME_MINUTES}m"
    }

    fun blocksRemaining(targetHeight: UInt, currentHeight: UInt): Int =
        maxOf(0, (targetHeight.toInt() - currentHeight.toInt()))
}
