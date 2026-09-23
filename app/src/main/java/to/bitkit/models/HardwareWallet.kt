package to.bitkit.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.synonym.bitkitcore.AccountType
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.TrezorScriptType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.Serializable

/** A paired hardware wallet tracked as a watch-only balance. */
@Stable
data class HwWallet(
    val id: String,
    val name: String,
    val model: String?,
    val transportType: TransportType,
    val isConnected: Boolean,
    val balanceSats: ULong,
    val activities: ImmutableList<Activity>,
    val fundingBalanceSats: ULong = balanceSats,
    val deviceIds: ImmutableSet<String> = persistentSetOf(id),
    val passphraseProtected: Boolean = false,
)

/** Serializable per-device balance snapshot carried by [BalanceState]. */
@Immutable
@Serializable
data class HwWalletBalance(
    val id: String,
    val sats: ULong,
)

/** A newly detected inbound transaction to a watched hardware wallet. */
@Immutable
data class HwWalletReceivedTx(
    val txid: String,
    val sats: ULong,
    val walletId: String,
)

/** The next unused external address for a paired hardware-wallet account. */
@Immutable
data class HwReceiveAddress(
    val address: String,
    val path: String,
    val addressType: HwFundingAddressType,
)

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

data class HwFundingTransaction(
    val psbt: String,
    val miningFeeSats: ULong,
    val feeRate: Float,
    val totalSpent: ULong,
    val satsPerVByte: ULong,
)

data class HwFundingSignedTx(
    val serializedTx: String,
    val miningFeeSats: ULong,
    val feeRate: ULong,
    val totalSpent: ULong,
)

data class HwFundingBroadcastResult(
    val txId: String,
    val miningFeeSats: ULong,
    val feeRate: ULong,
    val totalSpent: ULong,
)

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

    val trezorScriptType: TrezorScriptType
        get() = when (this) {
            LEGACY -> TrezorScriptType.SPEND_ADDRESS
            NESTED_SEGWIT -> TrezorScriptType.SPEND_P2SH_WITNESS
            NATIVE_SEGWIT -> TrezorScriptType.SPEND_WITNESS
            TAPROOT -> TrezorScriptType.SPEND_TAPROOT
        }

    companion object {
        val DEFAULT: HwFundingAddressType = entries.first { it.addressType == DEFAULT_ADDRESS_TYPE }
    }
}

fun HwWallet.toBalance() = HwWalletBalance(id = id, sats = balanceSats)
