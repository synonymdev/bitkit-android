# Amount-limit journeys

These journeys exercise the "block numberpad input exceeding the max/available amount"
behaviour added on `fix/block-input-over-max`. The same `AmountInputViewModel.setMaxAmount` +
`MaxExceeded` effect path backs all three amount-entry screens (Send, Transfer→Spending,
External node).

## What the feature does
- Typing a digit that would push the amount **over the cap is rejected** — the display stays at
  the largest value still within the cap (e.g. tapping `9` repeatedly stops at `9 999` when the
  cap is `98 064`, because `99 999` would exceed it).
- A **short (1.5s) WARNING toast** is emitted on the first rejected keypress.
- **Delete is always allowed**, even when sitting at the cap.

## Mandatory setup (learned the hard way)
1. **Fund a real, positive available balance first.** With `0` available, `setMaxAmount(0)`
   falls back to the global `MAX_AMOUNT` cap (the code only applies the limit when `amount > 0`),
   so nothing gets blocked and the journeys silently pass for the wrong reason.
   - Get an on-chain (Savings) address from Receive → Show Details.
   - Fund + mine via the `lsp` skill:
     `./lsp POST /regtest/chain/deposit '{"address":"<addr>","amountSat":100000}'`
     then `./lsp POST /regtest/chain/mine '{"count":3}'` and wait for the balance to sync.
2. **Transfer→Spending needs Spark connected** so the most Savings can send to the Spark deposit
   address can be priced. On regtest it also needs a build with `ESPLORA_URL` on Spark's regtest chain
   (`scripts/spark-regtest-esplora.py`); without it the Transfer Unavailable sheet opens. The number pad is disabled until that max loads — **wait for it** before
   typing.
3. **External-node flow needs a reachable LN peer.** The staging LSP node works as the peer:
   id `028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc`,
   host `34.65.86.104`, port `9400` (from `./lsp GET /info`).

## Gotchas
- **The cap can be lower than the visible "Available".** Fee/channel reserves mean e.g. Available
  `99 890` but the max is `98 064`. Assert "does not exceed the **stated maximum**",
  not "Available".
- **Do not assert Continue is disabled when over the max.** Because the input is *capped* (never
  left in an over-max state), the capped value is valid and Continue stays **enabled**.
- The toast is WARNING type and lasts ~1.5s — verify it immediately after the over-max keypress.
- `adb input text` can drop characters in dotted strings (host IPs) — type digit groups + dots
  separately, then verify.
- Full-res screenshots can exceed image limits; prefer `android layout` JSON and tap elements by
  their test tag (`N9`, `NRemove`, etc.).

## Test tags used
- NumberPad keys: digits `N0`–`N9`, triple-zero `N000`, decimal `NDecimal`, delete `NRemove`.
- Send: screen `send_amount_screen`, field `SendNumberField`, available `available_balance`,
  max `SendAmountMax`, continue `ContinueAmount`; recipient `RecipientManual` / `RecipientInput`
  / `AddressContinue`; home Send button `Send`.
- Transfer→Spending: screen `SpendingAmount`, field `SpendingAmountNumberField`,
  available `SpendingAmountAvailable`, 25% `SpendingAmountQuarter`, max `SpendingAmountMax`,
  continue `SpendingAmountContinue`.
- External: funding `FundManual`; connection `NodeIdInput`/`HostInput`/`PortInput`/`ExternalContinue`;
  amount screen `ExternalAmount`, field `ExternalAmountNumberField`, 25% `ExternalAmountQuarter`,
  max `ExternalAmountMax`, continue `ExternalAmountContinue`.

## Spending immediately after a Lightning receipt

`send-spending-after-receive.xml` covers the Android channel-cache regression. A usable channel can
retain its old outbound limit after a receipt even though the displayed Spending balance is fresh.
Opening an amountless invoice must refresh that channel snapshot before deriving the input limit.
The existing `@send_2` fixture in `synonymdev/bitkit-e2e-tests` prepares a fresh local LND channel,
receives 10 000 sats, checks rejection of 10 001, then exercises normal payments and reentry. Keep
its toast and retained-field assertions; an additional rejected digit can hide the original failure.

This fixes Android repository state publication; no feature is being ported and iOS source is
unchanged. The shared Send2 case already asserts the same over-limit behavior on iOS.
