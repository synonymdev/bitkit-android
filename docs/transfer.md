# Transfer System

## Domain Language

**Transfer Directions:**
- **Transfers to Spending** = Moving from savings (onchain) → spending (lightning)
  - Channel orders: Pay for channel to LSP
  - Manual channels: Fund channel to non-LSP node
- **Transfers to Savings** = Moving from spending (lightning) → savings (onchain)
  - Channel closes: Funds swept from spending to savings

## Overview
Transfers are first-class domain objects that track funds moving between spending and savings. They enable proper balance calculations and UI display of pending transfers.

## Core UX Requirement

**Pending transfers must NOT appear in balance calculations:**

When a transfer is active (not settled):
- User sees transfer in "Incoming Transfer" UI
- Funds are NOT included in destination balance
- Funds are NOT included in source balance (if still visible in LDK)

When a transfer is settled:
- Transfer disappears from "Incoming Transfer" UI
- Funds appear in destination balance
- User can now spend/use the funds

**This applies consistently to all transfer types:**
- Channel orders (LSP)
- Manual channels
- Channel closes (coop/force)

**Why:** Users expect to see only spendable funds in their balances. Pending transfers are in-flight and not yet usable.

## Transfer Types

### TransferType Enum
- `TO_SPENDING`: LSP channel purchase
- `MANUAL_SETUP`: Manual channel open
- `TO_SAVINGS`: Generic channel close
- `COOP_CLOSE`: Cooperative channel close
- `FORCE_CLOSE`: Force channel close

See: `TransferType.kt`

### Type Categories

Transfers are grouped into two categories:
- **toSpending**: `TO_SPENDING`, `MANUAL_SETUP` (funds moving from savings → spending)
- **toSavings**: `TO_SAVINGS`, `COOP_CLOSE`, `FORCE_CLOSE` (funds moving from spending → savings)

See: `TransferType.kt` for category extension functions

## Data Model

### TransferEntity

See: `TransferEntity.kt`

**Field Descriptions:**
- `id`: UUID primary key (immutable)
- `type`: Transfer type enum
- `amountSats`: Amount being transferred
- `channelId`: LDK channel ID (for manual channels only; LSP channels use lspOrderId)
- `fundingTxId`: Channel funding transaction ID
- `lspOrderId`: Blocktank order ID (LSP flow only)
- `isSettled`: false = affects balances, true = complete
- `createdAt`: Unix timestamp (seconds) when transfer created
- `settledAt`: Unix timestamp (seconds) when settled

## Lifecycle Flows

### LSP Channel Purchase (TO_SPENDING)

**Phase 1: Payment**
```
User confirms amount
    ↓
Create Blocktank order
    ↓
Send onchain payment to LSP
    ↓
CREATE TRANSFER:
  id = UUID
  type = TO_SPENDING
  amountSats = order.clientBalanceSat
  channelId = null (not available yet)
  fundingTxId = null
  lspOrderId = order.id
  isSettled = false
```

**Phase 2: Channel Appears**
```
Poll order status OR receive ChannelPending event
    ↓
Channel appears in LDK with fundingTxo
    ↓
Just-in-time resolution via lspOrderId:
  order.channel.fundingTx.id → match LDK channel.fundingTxo.txid
```

**Phase 3: Channel Ready**
```
ChannelReady event OR isChannelReady becomes true
    ↓
SETTLE TRANSFER:
  isSettled = true
  settledAt = now
```

### Manual Channel Open (MANUAL_SETUP)

**Phase 1: Open**
```
User enters node URI + amount
    ↓
Call lightningRepo.openChannel()
    ↓
Await ChannelPending event
    ↓
CREATE TRANSFER:
  id = UUID
  type = MANUAL_SETUP
  amountSats = channelValueSats
  channelId = event.channelId (available immediately!)
  fundingTxId = event.fundingTxo.txid
  lspOrderId = null
  isSettled = false
```

**Phase 2: Channel Ready**
```
ChannelReady event OR isChannelReady becomes true
    ↓
SETTLE TRANSFER:
  isSettled = true
  settledAt = now
```

### Channel Close (COOP_CLOSE / FORCE_CLOSE)

**Phase 1: Close Initiated**
```
User selects channels to close
    ↓
Call closeChannel(force = true/false)
    ↓
CREATE TRANSFER:
  id = UUID
  type = COOP_CLOSE or FORCE_CLOSE (WE KNOW which!)
  amountSats = channel.balanceSats
  channelId = channel.channelId
  fundingTxId = channel.fundingTxo.txid
  lspOrderId = null
  isSettled = false
```

**Phase 2: Channel Closed**
```
ChannelClosed event fires
    ↓
Channel removed from list_channels()
    ↓
Balance still in lightning_balances
    ↓
Transfer remains active
```

**Phase 3: Funds Swept**
```
Output sweeper broadcasts claim tx
    ↓
Claim tx confirms
    ↓
Balance removed from lightning_balances
    ↓
SETTLE TRANSFER:
  isSettled = true
  settledAt = now
```

## State Management

### Channel ID Resolution

**Just-in-time channelId resolution** for settlement checks (NOT for balance calculation).

For LSP orders:
1. Fetch order from blocktankRepo using `transfer.lspOrderId`
2. Extract `fundingTxId` from `order.channel.fundingTx.id`
3. Match LDK channel by `fundingTxo.txid`
4. Return matched `channelId`

For manual transfers:
- Return `transfer.channelId` directly (already populated)

**Usage:**
- Used in `TransferRepo.syncTransferStates()` to check if transfer should be settled
- NOT used for balance calculation during pending phase (see Balance Calculation section)

**Benefits:**
- Single source of truth: Blocktank order data
- No state updates needed
- Reliable fundingTxId matching
- Only resolves on-demand

See: `TransferRepo.resolveChannelIdForTransfer()`

### Creation Points
- TO_SPENDING: When payment sent (`TransferViewModel.onTransferToSpendingConfirm`)
- MANUAL_SETUP: When ChannelPending event received (`ExternalNodeViewModel.onConfirm`)
- COOP_CLOSE: When close initiated (`TransferViewModel.closeSelectedChannels`)
- FORCE_CLOSE: When force close initiated (`TransferViewModel.forceTransfer`)

### Update Points
- All types: When sync detects state changes (`TransferRepo.syncTransferStates`)
- Just-in-time channelId resolution via `resolveChannelIdForTransfer()`

### Settlement Conditions
- toSpending (TO_SPENDING/MANUAL_SETUP): When channel.isChannelReady == true
- toSavings (TO_SAVINGS/COOP_CLOSE/FORCE_CLOSE): When balance.channelId not in lightning_balances

### Event-Driven Updates
Listen to LDK events for immediate state transitions:
- ChannelReady → Check and settle toSpending transfers
- ChannelClosed → Already created transfer, no action

### Polling Updates
`syncTransferStates()` runs periodically to catch missed events:
- Check all active toSpending transfers for isChannelReady
- Check all active toSavings transfers for balance absence

## Activity Correlation

### Onchain Activities
Channel funding transactions are marked as transfers via `TransactionMetadata` stored in cache. Activity sync applies this metadata to display "Transfer to Spending" label in UI.

See: `ActivityRepo.kt` for metadata application logic

### Correlation Fields
- TO_SPENDING/MANUAL_SETUP: `Activity.txId == Transfer.fundingTxId`
- TO_SAVINGS/etc: `Activity.channelId == Transfer.channelId`

## Balance Calculation using Transfers

Transfer states are used to adjust displayed balances to match UX requirements.

**toSpending transfers (savings → spending):**
- **Channel orders** (`lspOrderId` exists):
  - Payment SENT to LSP from onchain wallet
  - LDK's `totalOnchainBalanceSats` already reflects sent payment
  - No adjustment needed - balance already correct
- **Manual channels** (`channelId` exists):
  - Funding tx SENT (UTXO spent from wallet)
  - LDK's `totalOnchainBalanceSats` already reflects spent UTXO
  - LDK includes channel balance in `totalLightningBalanceSats` (pending channel)
  - Subtract channel balance from spending only (hide pending channel)
  - No savings adjustment needed - funding tx already reflected
  - Only subtract channels that exist in LDK but aren't ready yet

**toSavings transfers (spending → savings):**
- Channel closed, funds being swept to onchain wallet
- LDK includes sweep balance in `totalLightningBalanceSats` (in `lightningBalances` array)
- LDK does NOT yet include closing funds in `totalOnchainBalanceSats` (until sweep confirms)
- Subtract sweep balance from spending only (hide pending sweep)
- No savings adjustment needed - closing funds not yet in onchain balance
- **Result:** User sees funds in "Incoming Transfer" UI, not in either balance
- **LSP orders**: Resolve channelId via order, then find in `lightningBalances`
- **Direct channels**: Use channelId directly, then find in `lightningBalances`

See: `DeriveBalanceStateUseCase.kt`

## Edge Cases & Failure Handling

### LSP Order Expires
```
Detection: Order state == EXPIRED
Action: Mark transfer as settled (it failed, no longer affects balance)
Consider: Add failureReason field to track this
```

### App Restarts During Transfer
```
Recovery: On app startup, syncTransferStates() checks all active transfers
Matches to current LDK state and settles if appropriate
```

### Channel ID Resolution (LSP Flow)
```
Just-in-time resolution via resolveChannelIdForTransfer():
1. If transfer.lspOrderId exists:
   - Fetch order from blocktankRepo
   - Extract fundingTxId from order.channel.fundingTx.id
   - Match LDK channel by fundingTxo.txid
   - Return matched channelId
2. Otherwise: return transfer.channelId (manual channels)

No state updates needed - channelId resolved on-demand when checking transfer state
```

### Coop Close Becomes Force Close
```
Update transfer type:
transferRepo.updateType(transferId, FORCE_CLOSE)
```

### Duplicate Transfer Prevention
```
Before creating transfer, check:
- For TO_SPENDING: No active transfer with same lspOrderId
- For MANUAL_SETUP: No active transfer with same channelId
- For closes: No active transfer with same channelId
```

## Backup & Restore

### Backup Category: "Boosts & Transfers"

Transfers are serialized to JSON and included in backup. Restore deserializes and inserts into database.

### Cleanup Policy
- Keep settled transfers for 30 days
- Allows backup to include recent history
- Automatic cleanup via periodic job

## Testing Scenarios

### TO_SPENDING Happy Path
1. Create order, send payment
2. Verify transfer created with lspOrderId
3. Wait for channel to appear
4. Wait for ChannelReady
5. Verify transfer settled

### MANUAL_SETUP Happy Path
1. Open channel
2. Verify transfer created on ChannelPending
3. Wait for ChannelReady
4. Verify transfer settled

### COOP_CLOSE Happy Path
1. Close channel
2. Verify transfer created
3. Wait for balance to disappear
4. Verify transfer settled

### App Restart During Transfer
1. Create transfer
2. Kill app
3. Restart app
4. Verify transfer still active and tracks correctly

### LSP Order Balance and Settlement
1. Create transfer with lspOrderId (channelId = null)
2. Verify balance uses transfer.amountSats immediately (before channel exists in LDK)
3. Wait for order.channel to populate (channel appears in LDK)
4. Verify balance still uses transfer.amountSats (or channel balance if available)
5. Wait for channel to become ready
6. Verify transfer is settled via resolveChannelIdForTransfer() + isChannelReady check

## Functional Tests

### 1. To Spending - LSP Channel Purchase

- transfer to spending
  - do not wait on the last animated screen, instead
  - tap quickly on 'continue using the app' → back home
- **expect:**
  - savings balance: DECREASED by transfer amount
  - spending balance: 0 + transfer icon
  - activity: new UNCONFIRMED transfer entry
- tap spending → **expect** incoming transfer balance → back home
- mine in slack: `/regtest-mine 1`
- **expect:**
  - savings balance: UNCHANGED
  - spending balance: INCREASED by channel balance + no transfer icon
  - activity: entry CONFIRMED

### 2. To Savings - LSP Channel Coop Close

- transfer to savings → back home
- **expect:**
  - savings balance: UNCHANGED + transfer icon
  - spending balance: 0
  - activity: new UNCONFIRMED entry [^1]
  - suggestions: new initiating card
- tap savings → **expect** incoming transfer balance → back home
- mine in slack: `/regtest-mine 6`
- **expect:**
  - savings balance: INCREASED by transfer amount + no transfer icon
  - spending balance: 0
  - activity: entry CONFIRMED

### 3. Manual Setup - Fund Channel to External Node

- uninstall app
- open a channel manually: [bitkit-docker: External Node Channel][external-node-channel]
  - **do not mine** the last `6` blocks yet!
- **expect:**
  - savings balance: DECREASED by transfer amount
  - spending balance: 0 + new transfer icon
  - activity: new unconfirmed transfer entry
- tap spending → **expect** incoming transfer balance → back home
- mine: `./bitcoin-cli mine 6`
- **expect:**
  - savings balance: UNCHANGED
  - spending balance: INCREASED by transfer amount + no transfer icon
  - activity: entry CONFIRMED

### 4. Coop Close - Manual Channel Coop Close

- (optional: if not ran right after test 2):
  - uninstall app
  - open a channel manually: [bitkit-docker: External Node Channel][external-node-channel]
- transfer to savings → back home
- **expect:**
  - savings balance: UNCHANGED + new transfer icon
  - spending balance: 0
  - activity: new UNCONFIRMED entry [^1]
  - suggestions: new initiating card
- tap savings → **expect:** incoming transfer balance → back home
- mine: `./bitcoin-cli mine 6`
- **expect:**
  - savings balance: INCREASED by transfer amount + no transfer icon
  - spending balance: 0
  - activity: entry CONFIRMED

### 5. Force Close - Manual Channel Force Close

- in `TransferViewModel.kt:55-56` use:
    ```kt
    const val RETRY_INTERVAL_MS = 15_000L  
    const val GIVE_UP_MS = 15_000L
    ```
- uninstall app
- open a channel manually: [bitkit-docker: External Node Channel][external-node-channel]
- drawer → app status → lightning node → disconnect peer with port `9735`
- transfer to savings → back home → wait ± 15 sec
- **expect:** force transfer sheet → tap 'force transfer'
- **expect:**
  - sheet closed
  - savings balance: UNCHANGED + new transfer icon
  - spending balance: 0
- tap savings → **expect:** incoming transfer balance → back home
- mine: `./bitcoin-cli mine 144`
- **expect:**
  - savings balance: INCREASED by transfer amount + no transfer icon
  - spending balance: 0
  - activity: new UNCONFIRMED entry [^1]
  - - suggestions: new initiating card (?)
- mine: `./bitcoin-cli mine 1`
- **expect:** activity: entry CONFIRMED

[^1]: Currently we can't display the channel closure transactions as 'transfer' yet in the activity list, due to an api missing in ldk-node. See comment in [ldk-node/wallet/mod.rs#L728-L738][ldk-node-comment]

[external-node-channel]: https://github.com/synonymdev/bitkit-docker#external-node-channel
[ldk-node-comment]: https://github.com/lightningdevkit/ldk-node/blob/22a5d7742cf4e9265173ae51106db4bd9668ec8a/src/wallet/mod.rs#L728-L738
