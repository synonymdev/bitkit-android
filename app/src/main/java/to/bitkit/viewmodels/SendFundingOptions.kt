package to.bitkit.viewmodels

internal fun SendFundingSource.isSoftwareBitcoin(): Boolean =
    this == SendFundingSource.Savings || this == SendFundingSource.Spending

internal fun List<SendFundingSource>.withSingleBitcoinSource(preferred: SendFundingSource): List<SendFundingSource> {
    val selected = preferred.takeIf { it in this } ?: firstOrNull { it.isSoftwareBitcoin() }
    return filter { !it.isSoftwareBitcoin() || it == selected }
}

internal fun SendUiState.preferredSoftwareFundingSource(): SendFundingSource = when {
    hardwareWalletId == null && !paykitUsesUsdt && payMethod == SendMethod.ONCHAIN -> SendFundingSource.Savings
    isUnified || decodedInvoice != null || lnurl is LnurlParams.LnurlPay -> SendFundingSource.Spending
    else -> SendFundingSource.Savings
}
