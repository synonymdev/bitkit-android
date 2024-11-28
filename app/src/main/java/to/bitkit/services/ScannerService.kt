package to.bitkit.services

import uniffi.bitkitcore.OnChainInvoice
import uniffi.bitkitcore.Scanner
import uniffi.bitkitcore.ValidationResult
import javax.inject.Inject

class ScannerService @Inject constructor() {
    suspend fun decode(input: String): Scanner {
        return uniffi.bitkitcore.decode(input)
    }

    fun validateBitcoinAddress(input: String): ValidationResult {
        return uniffi.bitkitcore.validateBitcoinAddress(input)
    }
}

fun OnChainInvoice.hasLightingParam(): Boolean {
    return params?.containsKey("lightning") == true
}

fun OnChainInvoice.lightningParam(): String? {
    return params?.get("lightning")
}
