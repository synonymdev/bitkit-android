# Channel-ready notification journeys

`non-cjit-channel-no-payment-notification.xml` reproduces issue #3 from PR #787 (`fix/channelready-cjit`): opening a
regular (non-CJIT) Lightning channel must not be reported as a received payment. Only a channel that carries a
Blocktank CJIT entry may produce a "Payment Received" notification, and a regular channel opening shows only the in-app
"Spending balance ready" toast.

## Mandatory setup

1. **Onboarded dev (regtest) wallet** with a positive on-chain balance.
2. **A channel opened through Manual Setup** (Settings › Advanced › Lightning Connections › add connection › Manual
   Setup) to the staging LSP node from `./lsp GET /info`, then mined with `./lsp POST /regtest/chain/mine`.
3. Read notifications back with `adb shell dumpsys notification --noredact | grep -A3 -i "to.bitkit"`.
