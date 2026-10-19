# Coin Selection

`manual-wallet-switch.xml` is shared with iOS. It covers manual selection after switching
to Savings, repeated Back and swipe, Tags navigation after selection, and automatic mode.
No payment is submitted. iOS can open the picker on the wallet switch; Android opens it on
the next swipe. Both return to confirmation when backing out of that picker. Android wallet
switching on Amount stays on Amount; Continue opens manual selection.

Use a disposable regtest wallet with confirmed Savings coins, sufficient Spending balance,
and a fresh external unified invoice. See [backend setup](../README.md#backend-preconditions).
Open the invoice using the URI intent described there. Coin Selection is under Settings > Advanced.
On confirmation, `SendConfirmToggleDetails` exposes `SendConfirmAssetButton`. The picker uses
`coin_selection_screen` and `continue_button`; Tags uses `TagsAddSend`.

The other two journeys remain Android-only; see their XML descriptions for UTXO and funding
preconditions:

- `manual-coin-selection.xml` checks the UTXO-only list with no Auto row, selected and required
  totals, and Continue gating.
- `manual-coin-selection-load.xml` checks the initial UTXO load and preservation of the selection
  across incoming activity.
