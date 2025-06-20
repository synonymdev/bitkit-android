package to.bitkit.services

import com.synonym.bitkitcore.OnChainInvoice
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.ValidationResult
import javax.inject.Inject

class ScannerService @Inject constructor() {
    suspend fun decode(input: String): Scanner {
        return com.synonym.bitkitcore.decode(input)
    }

    fun validateBitcoinAddress(input: String): ValidationResult {
        return com.synonym.bitkitcore.validateBitcoinAddress(input)
    }
}

fun OnChainInvoice.hasLightingParam(): Boolean {
    return params?.containsKey("lightning") == true
}

fun OnChainInvoice.lightningParam(): String? {
    return params?.get("lightning")
}
