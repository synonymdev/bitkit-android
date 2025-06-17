package to.bitkit.models

import org.lightningdevkit.ldknode.CoinSelectionAlgorithm

// TODO: Use CoinSelectionAlgorithm?
enum class CoinSelectionPreference {
    SmallestFirst,
    LargestFirst,
    Consolidate,
    FirstInFirstOut,
    LastInFirstOut,
}

fun CoinSelectionPreference.toCoinSelectAlgorithm(): CoinSelectionAlgorithm {
    val default = CoinSelectionAlgorithm.BRANCH_AND_BOUND
    return when (this) {
        CoinSelectionPreference.SmallestFirst -> return default // missing match
        CoinSelectionPreference.LargestFirst -> return CoinSelectionAlgorithm.LARGEST_FIRST
        CoinSelectionPreference.Consolidate -> return CoinSelectionAlgorithm.LARGEST_FIRST // approx match
        CoinSelectionPreference.FirstInFirstOut -> return CoinSelectionAlgorithm.OLDEST_FIRST
        CoinSelectionPreference.LastInFirstOut -> default
    }
}
