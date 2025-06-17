package to.bitkit.models

import org.lightningdevkit.ldknode.CoinSelectionAlgorithm

enum class CoinSelectionPreference {
    SmallestFirst,
    LargestFirst,
    Consolidate,
    FirstInFirstOut,
    LastInFirstOut,
    BranchAndBound,
    SingleRandomDraw,
}

fun CoinSelectionPreference.toCoinSelectAlgorithm(): Result<CoinSelectionAlgorithm> {
    return when (this) {
        CoinSelectionPreference.SmallestFirst -> Result.failure(
            NotImplementedError("SmallestFirst not implemented")
        )

        CoinSelectionPreference.LargestFirst -> Result.success(
            CoinSelectionAlgorithm.LARGEST_FIRST
        )

        CoinSelectionPreference.Consolidate -> Result.failure(
            NotImplementedError("Consolidate not implemented") // approx match: LARGEST_FIRST
        )

        CoinSelectionPreference.FirstInFirstOut -> Result.success(
            CoinSelectionAlgorithm.OLDEST_FIRST
        )

        CoinSelectionPreference.LastInFirstOut -> Result.failure(
            NotImplementedError("LastInFirstOut not implemented")
        )

        CoinSelectionPreference.BranchAndBound -> Result.success(
            CoinSelectionAlgorithm.BRANCH_AND_BOUND
        )

        CoinSelectionPreference.SingleRandomDraw -> Result.success(
            CoinSelectionAlgorithm.SINGLE_RANDOM_DRAW
        )
    }
}
