# Channels closing journeys

Synonym is winding down Blocktank, its Lightning service provider. synonymdev/bitkit-android#1669 removed
every path that buys a new Lightning channel from it and added a notice for users who still have one.
The iOS twin is synonymdev/bitkit-ios#1097.

## What the notice does

- **Home banner** `ChannelsClosingBanner`, "CHANNELS CLOSE ON DEC 15, 2026", built from the same
  component as the "TRANSFER IN PROGRESS" banner. It shows for a wallet with a Lightning channel, open
  or pending, to a Blocktank (trusted LSP) node, whether or not the wallet has activity; channels to other
  nodes (LNURL channel, manual setup to a third-party node) do not show it. Tapping it opens the Spending
  wallet.
- **Spending wallet notice** `ChannelsClosingNotice` under the balance header, with the same date and the
  same Blocktank-channel condition.
- The date is a placeholder constant (Dec 15, 2026) until the shutdown date is final. Journeys assert the
  banner and the notice agree and read the date from the screen instead of hardcoding it.
- **Unavailable sheet:** Savings "Transfer To Spending" (`TransferToSpending`), Spending "Transfer from
  Savings" (`TransferFromSavings`) and hardware wallet "Transfer To Spending" (`HardwareTransferToSpending`)
  stay, but open the "New Channels Unavailable" sheet (`ChannelsUnavailableSheet`) instead of the transfer
  flow. For a wallet with a Blocktank channel it also shows the close date (`ChannelsUnavailableCloseDate`);
  the OK button (`ChannelsUnavailableButton`) closes it and leaves the user on the same screen.
- **Removed:** the Home "Spend" suggestion card (`Suggestion-lightning`), the Receive sheet's CJIT button
  and gift codes.
  Transfer To Savings, send, receive over an existing channel, LNURL channel and manual external node
  stay.

## Mandatory setup

1. **A wallet with an open Lightning channel.** See [Getting a Lightning channel](../README.md#getting-a-lightning-channel):
   install a build from before synonymdev/bitkit-android#1669, open a channel with Transfer To Spending,
   then install this build over it; or open a manual channel to the staging LSP node from Settings ›
   Advanced › Lightning Connections › add connection › Manual Setup.

## Journeys

| Journey | Covers |
| --- | --- |
| `channels-closing-notice.xml` | Banner on Home → tap → Spending wallet notice and Transfer To Savings; Savings Transfer To Spending opens the New Channels Unavailable sheet with the close date |
| [`../receive/receive-onchain-only-without-channel.xml`](../receive/receive-onchain-only-without-channel.xml) | The opposite precondition: a funded wallet with no channels shows no banner, no Spend card and a Savings-only Receive sheet |

## Test tags

`ChannelsClosingBanner`, `ChannelsClosingNotice`, `ActivitySavings`, `ActivitySpending`, `TotalBalance`,
`TransferToSavings`, `TransferToSpending`, `ChannelsUnavailableSheet`, `ChannelsUnavailableCloseDate`,
`ChannelsUnavailableButton`.
