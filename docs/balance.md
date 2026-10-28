# Balance Management in Bitkit

## Domain Language

- **Spending** = Lighting balance
- **Savings** = Onchain balance

## Core UX Requirement

**Pending transfers must NOT appear in balance calculations:**

- Funds being transferred TO spending: NOT shown in spending balance until transfer settled
- Funds being transferred TO savings: NOT shown in savings balance until transfer settled
- User sees funds in "Incoming Transfer" UI, not in available balance
- This applies consistently regardless of transfer type (LSP order, manual channel, channel close)

**Why:** Users should only see spendable/usable funds in their balances. Pending transfers are in-flight and not yet available for use.

## Why Manual Balance Adjustments Are Needed

LDK-node returns raw blockchain state, which doesn't match our UX requirements:

**LDK-node reports balances as-is from blockchain/channel state:**
- `totalOnchainBalanceSats`: All UTXOs in wallet (confirmed + unconfirmed)
- `totalLightningBalanceSats`: All claimable lightning balances
- No concept of "pending transfers" - just reports what exists

**Our UX requirements differ:**
- Hide pending channel funds until ready
- Hide pending sweep outputs until settled
- Show consistent behavior across all transfer types

**Solution:** Derive adjusted balances by subtracting active transfers from LDK's raw balances.

See: `DeriveBalanceStateUseCase.kt`

## LDK-Node Balance Mechanics

### BalanceDetails Structure
LDK-node provides balance information through `BalanceDetails`:
- `total_onchain_balance_sats`: Total onchain wallet balance (all UTXOs including unconfirmed)
- `spendable_onchain_balance_sats`: Spendable balance
  - Excludes: unconfirmed amounts + anchor reserves
  - **DOES NOT exclude channel funding UTXOs** - they remain in the balance
- `total_lightning_balance_sats`: Sum of all claimable lightning balances
- `lightning_balances`: Detailed list of balance types per channel
- `pending_balances_from_channel_closures`: Delayed outputs being swept (CSV timelock cases)

### Lightning Balance Types
`lightning_balances` contains different balance variants:

**ClaimableOnChannelClose**
- Appears: Channel is open (pending or ready)
- Meaning: Amount claimable if channel force-closes now
- Key insight: Appears IMMEDIATELY when channel enters pending state
- Stays until: Channel closes

**ClaimableAwaitingConfirmations**
- Appears: After channel close, output awaiting confirmations
- Meaning: Balance is ours, waiting for blockchain confirmations

**ContentiousClaimable**
- Appears: Dispute scenario
- Meaning: Must claim before timeout or counterparty might take it

**MaybeTimeoutClaimableHTLC / MaybePreimageClaimableHTLC**
- HTLCs that might be claimable under certain conditions

**CounterpartyRevokedOutputClaimable**
- Counterparty broadcast revoked state
- We can claim all outputs

### Balance State Machine - Channel Open

```
User initiates channel open
         ↓
Funding tx broadcast
         ↓
ChannelPending event fires
         ↓
Monitor created with funding_spend_confirmed = None
         ↓
get_claimable_balances() returns ClaimableOnChannelClose
         ↓
total_lightning_balance_sats INCLUDES channel value ← IMMEDIATE
         ↓
total_onchain_balance_sats INCLUDES funding UTXO
         ↓
[Funding tx confirms]
         ↓
ChannelReady event fires
         ↓
Channel becomes usable
         ↓
total_lightning_balance_sats STILL includes channel value
total_onchain_balance_sats STILL includes funding UTXO
```

**Key Insight:** LDK does NOT remove funding UTXO from onchain balance.

**Double Counting Issue:** During channel open, the same sats appear in:
1. `total_onchain_balance_sats` (as funding UTXO)
2. `total_lightning_balance_sats` (as ClaimableOnChannelClose)

### Balance State Machine - Channel Close

```
Channel close initiated
         ↓
ChannelClosed event fires
         ↓
Channel removed from list_channels()
         ↓
Balance remains in lightning_balances (various types)
         ↓
Output sweeper claims funds
         ↓
[Normal case: NO entry in pending_balances_from_channel_closures]
[CSV delay case: Entry in pending_balances_from_channel_closures]
         ↓
Sweep tx confirms
         ↓
Balance removed from lightning_balances
         ↓
Funds appear in total_onchain_balance_sats
```

**Key Insight:** `pending_balances_from_channel_closures` is ONLY for delayed outputs (CSV timelocks). Normal coop/force closes show balances in `lightning_balances` until swept.

## How ClaimableOnChannelClose Is Calculated

### The Formula

The claimable amount equals our total channel balance (`to_self_value_sat`) plus any pending inbound HTLCs, excluding on-chain fees.

### Understanding Channel Reserves

Lightning channels have two reserves:

**holder_selected_channel_reserve** (aka `unspendable_punishment_reserve`):
- The reserve **WE** must hold for ourselves
- Cannot be spent over Lightning
- Ensures counterparty can punish us if we broadcast revoked state
- Maps to `ChannelDetails.unspendablePunishmentReserve`

**counterparty_selected_channel_reserve** (aka `counterparty_unspendable_punishment_reserve`):
- The reserve **THEY** must hold for themselves
- We cannot spend it (it's their minimum balance)
- Ensures we can punish them if they broadcast revoked state
- Maps to `ChannelDetails.counterpartyUnspendablePunishmentReserve`

### Channel Balance Decomposition

The total channel value is split between our balance and the remote peer's balance.

Our balance in millisatoshis consists of our outbound capacity, pending outbound HTLCs, and the counterparty's reserve.

**Why counterparty's reserve is included:**
- `outbound_capacity_msat` excludes their reserve (we can't spend it while channel is open)
- On channel close, their reserve comes back to us as part of our final balance
- Therefore our value on close equals outbound capacity plus counterparty's reserve

### Reconstructing value_to_self_sat from ChannelDetails

To calculate our balance in the channel (what we'd get on close):
- Start with `outboundCapacityMsat / 1000` (what we can spend now)
- Add `counterpartyUnspendablePunishmentReserve` (their reserve, returned to us on close)

**Common Mistake:** Adding `unspendablePunishmentReserve` instead of `counterpartyUnspendablePunishmentReserve`:
- `unspendablePunishmentReserve` is OUR reserve (already excluded from capacity)
- `counterpartyUnspendablePunishmentReserve` is THEIR reserve (we get it back on close)

See: `ChannelDetails.valueToSelfSats` extension in `ChannelDetails.kt`

## Bitkit UX Requirements

### Displayed Balances Should Reflect Usable/Spendable Amounts

**Problem:** LDK Node's raw balances don't match our UX specs:
- Pending channel funding shows in lightning balance before usable
- Channel funding shows in both onchain and lightning (double count)

**Solution:** Adjust balances based on Transfer state

### Balance Adjustments

**toSpending transfers - Manual Channels (MANUAL_SETUP with channelId):**
- Funding tx has been SENT (UTXO spent from wallet)
- LDK's `totalOnchainBalanceSats` already reflects the spent UTXO
- LDK's `totalLightningBalanceSats` includes ClaimableOnChannelClose (pending channel)
- Subtract channel balance from spending only (hide pending channel)
- No onchain adjustment needed - funding tx already reflected
- **Result:** User sees correct onchain balance, spending shows 0 during pending

**toSavings transfers (TO_SAVINGS, COOP_CLOSE, FORCE_CLOSE):**
- Channel closed, funds being swept to onchain wallet
- LDK's `totalLightningBalanceSats` includes pending sweep balance (in `lightningBalances` array)
- LDK's `totalOnchainBalanceSats` does NOT yet include closing funds (until sweep tx confirms)
- Subtract sweep balance from spending only (hide pending sweep)
- No savings adjustment needed - closing funds not yet in onchain balance
- **Result:** User doesn't see pending sweep in either balance (shown in "Incoming Transfer" UI instead)

See: `DeriveBalanceStateUseCase.kt`

### Channel State for Incoming Transfers

**Do NOT use `isUsable`** - it becomes false when peer disconnects.

**Use `isChannelReady`** - only becomes true after:
- Funding tx confirmed
- channel_ready messages exchanged
- Stays true even if peer disconnects

## Edge Cases

**Manual Channel (MANUAL_SETUP):**
- Channel appears in pending state with its channelId
- Balance shows in total_lightning_balance_sats
- We subtract it until isChannelReady

**Coop Close:**
- Balance stays in lightning_balances as ClaimableAwaitingConfirmations
- We subtract it until it disappears (swept)

**Force Close:**
- Balance may have CSV delay
- Shows in lightning_balances with delay info
- We subtract it until it disappears

**Breach Remedy:**
- CounterpartyRevokedOutputClaimable appears
- We treat it as outgoing transfer and subtract
