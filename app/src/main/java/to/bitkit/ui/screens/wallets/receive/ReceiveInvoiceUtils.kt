package to.bitkit.ui.screens.wallets.receive

import to.bitkit.R
import to.bitkit.utils.Bip21Utils

/**
 * Returns the appropriate invoice/address for the selected tab.
 *
 * @param tab The selected receive tab
 * @param bip21 Full BIP21 invoice (onchain + lightning)
 * @param bolt11 Lightning invoice
 * @param cjitInvoice CJIT invoice from Blocktank (if active)
 * @param onchainAddress Pure Bitcoin address (fallback)
 * @return The invoice string to display/encode in QR
 */
@Suppress("LongParameterList")
fun getInvoiceForTab(
    tab: ReceiveTab,
    bip21: String,
    bolt11: String,
    cjitInvoice: String?,
    isNodeRunning: Boolean,
    canCreateLightningInvoice: Boolean = true,
    onchainAddress: String,
    hardwareAddress: String = "",
    hardwareAmountSats: ULong? = null,
    hardwareMessage: String = "",
): String {
    return when (tab) {
        ReceiveTab.SAVINGS -> {
            // Return BIP21 without lightning parameter to preserve amount and other parameters
            removeLightningFromBip21(bip21, onchainAddress)
        }

        ReceiveTab.AUTO -> {
            bip21.takeIf { isNodeRunning && canCreateLightningInvoice && containsLightningParameter(bip21) }
                ?: removeLightningFromBip21(bip21, onchainAddress)
        }

        ReceiveTab.SPENDING -> {
            // Lightning only: prefer CJIT > bolt11, empty when node is not running
            cjitInvoice?.takeIf { it.isNotEmpty() && isNodeRunning }
                ?: bolt11.takeIf { isNodeRunning && canCreateLightningInvoice }.orEmpty()
        }

        ReceiveTab.TREZOR -> hardwareAddress.takeIf(String::isNotBlank)?.let { address ->
            Bip21Utils.buildBip21Url(
                bitcoinAddress = address,
                amountSats = hardwareAmountSats?.takeUnless { it == 0uL },
                message = hardwareMessage,
            )
        }.orEmpty()
    }
}

/**
 * Returns the appropriate text to copy to clipboard for the savings tab.
 * Copies the plain address when there are no extra invoice details (no amount, no message),
 * or the BIP21 URI (without lightning) when extra details are present.
 *
 * @param bip21 Full BIP21 URI (onchain + optional lightning)
 * @param onchainAddress Plain Bitcoin address (fallback)
 * @return Plain address if no extra params, BIP21 URI without lightning if there are extra params
 */
fun getSavingsCopyText(bip21: String, onchainAddress: String): String {
    val bip21WithoutLightning = removeLightningFromBip21(bip21, onchainAddress)
    return if ('?' in bip21WithoutLightning) bip21WithoutLightning else onchainAddress
}

/**
 * Removes the lightning parameter from a BIP21 URI while preserving all other parameters.
 *
 * @param bip21 Full BIP21 URI (e.g., bitcoin:address?amount=0.001&lightning=lnbc...)
 * @param fallbackAddress Fallback address if BIP21 is empty or invalid
 * @return BIP21 URI without the lightning parameter (e.g., bitcoin:address?amount=0.001)
 */
fun removeLightningFromBip21(bip21: String, fallbackAddress: String): String {
    if (bip21.isBlank()) return fallbackAddress

    // Remove lightning parameter using regex
    // Handles both "?lightning=..." and "&lightning=..." cases
    val withoutLightning = bip21
        .replace(Regex("[?&]lightning=[^&]*"), "")
        .replace(Regex("\\?$"), "") // Remove trailing ? if it's the last char

    return withoutLightning.ifBlank { fallbackAddress }
}

/**
 * Checks if a BIP21 URI contains a lightning parameter.
 *
 * @param bip21 The BIP21 URI to check
 * @return true if the URI contains a lightning parameter, false otherwise
 */
private fun containsLightningParameter(bip21: String): Boolean {
    return Regex("[?&]lightning=[^&]*").containsMatchIn(bip21)
}

/**
 * Returns the appropriate QR code logo resource for the selected tab.
 *
 * @param tab The selected receive tab
 * @return Drawable resource ID for QR logo
 */
fun getQrLogoResource(tab: ReceiveTab): Int {
    return when (tab) {
        ReceiveTab.SAVINGS -> R.drawable.ic_btc_circle
        ReceiveTab.AUTO -> R.drawable.ic_unified_circle
        ReceiveTab.SPENDING -> R.drawable.ic_ln_circle
        ReceiveTab.TREZOR -> R.drawable.ic_btc_circle_blue
    }
}
