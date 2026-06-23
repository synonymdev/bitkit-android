package to.bitkit.models

import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.AddressType

sealed interface HwFundingAccount {
    val vendor: HwWalletVendor
    val xpub: String
    val addressType: HwFundingAddressType
    val accountType: AccountType
    val balanceSats: ULong

    data class Trezor(
        override val xpub: String,
        override val addressType: HwFundingAddressType,
        override val balanceSats: ULong,
    ) : HwFundingAccount {
        override val vendor: HwWalletVendor = HwWalletVendor.TREZOR
        override val accountType: AccountType
            get() = addressType.accountType
    }
}

enum class HwWalletVendor {
    TREZOR,
}

enum class HwFundingAddressType(
    val addressType: AddressType,
) {
    LEGACY(AddressType.P2PKH),
    NESTED_SEGWIT(AddressType.P2SH),
    NATIVE_SEGWIT(AddressType.P2WPKH),
    TAPROOT(AddressType.P2TR);

    val settingsKey: String
        get() = addressType.toSettingsString()

    val accountType: AccountType
        get() = addressType.toAccountType()

    companion object {
        val DEFAULT: HwFundingAddressType = entries.first { it.addressType == DEFAULT_ADDRESS_TYPE }
    }
}
