package to.bitkit.ui.screens.wallets.addTag

data class AddTagUIState(
    val tagsSuggestions: List<String> = listOf(),
    val tagInput: String = ""
)
