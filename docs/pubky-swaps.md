# Pubky Swaps

## Overview

Bitkit runs swaps between Lightning and onchain Bitcoin through a Pubky swap provider that the user connects by scanning its QR code. The provider replaces Boltz for new swaps. Swaps created earlier with Boltz keep recovering through the same updates stream.

A connected provider is used for:
- **Transfers to savings**: moving funds from the spending balance to an onchain savings address.
- **Paying addresses from spending**: paying an onchain address when savings cannot cover it, or when the user picks Spending as the funding source.

Channel opens still go through Blocktank (see [Channel provider seam](#channel-provider-seam)).

## Connecting a provider

Providers publish a connection link, usually as a QR code:

`pubkyswap://connect?pubky=<key>&network=<network>`

- `pubky` is the bare z-base-32 Pubky public key of the provider, in its canonical form and without the `pubky` prefix.
- `network` is one of `bitcoin`, `testnet`, `signet` or `regtest`. Bitkit supports `bitcoin` and `regtest`, and the network must match the app's network.

The link is accepted from the scanner, paste and clipboard. It is not a registered deep link. Parsing is strict: anything other than exactly these two query parameters, an authority, a path, a port or a fragment is rejected.

Scanning a valid link opens the connect sheet. Confirming it connects the bridge, reads the provider's terms to check that it answers, then saves the provider and switches swaps on. Saving happens only after that check, and a failed connection leaves the previous provider connected.

The saved provider is kept in local settings only. It is not part of the settings backup, so a restored wallet scans the provider again.

## Identity

Swaps are signed with the Pubky identity the user is signed in with in Bitkit, either the local key or the key shared by Pubky Ring. The key is read only while the bridge is configured, checked against the signed-in public key, and never stored by the swap code.

A user without a Pubky identity is sent to profile setup from the connect sheet. The scanned provider is kept in memory and its connect sheet reopens on the home screen once the identity is ready.

## Bridge lifecycle

`PubkySwapRepo` keeps the bitkit-core bridge bound to one combination of identity, provider and Electrum server:
- The same binding with another Electrum server reconnects in place.
- Another identity or provider first detaches the old binding. bitkit-core refuses that while swaps of the old binding are pending, and the old binding then stays connected so its swaps finish.
- Removing the provider or signing out detaches the bridge under the same rule.

The Android networking that the bridge needs is initialized on first use through the `PubkySwapInit` JNI bootstrap.

The swap updates stream runs while swaps are enabled, or while any swap still needs recovery. Reverse swap claims need a confirmed lockup, so they complete after at least one block.

## Data layout

Swap stores live under `files/pubky-swaps/<identity>/<network>/<provider>`, outside the wallet directories. Wiping the wallet disconnects the bridge and clears the saved provider, but keeps these stores and the swap database so funds of pending swaps stay recoverable.

These stores are not part of the backup yet.

## Fee policy

Every swap is checked against a fee and amount policy before it is created:
- Total provider fees of at most 10% of the locked amount.
- At most 1,000,000 sats per swap.

The same limits are passed to bitkit-core, which checks them again.

## Paying addresses from spending

For a payment of `R` sats to an address, the provider locks `L = R + claim fee` onchain, and Bitkit pays a hold invoice of `I = L + base fee + floor(L * fee ppm / 1,000,000) + lockup fee`. The claim transaction sends exactly `R` to the address and spends the rest as its fee. Bitkit computes these amounts itself so they match the provider's pricing exactly.

Before a swap is created:
- The quote is priced again with fresh terms. Changed terms show the new fees instead of creating the swap.
- The amount must be above dust and within the provider limits, and the invoice must fit the spending balance minus a routing reserve.

After creation, the returned lockup amount and invoice amount must match the quote, or nothing is paid.

Spending is offered for an address only outside hardware wallet, USDT, LNURL, unified invoice and Paykit contact or payment request flows.

## Paying hold invoices

A hold invoice is paid only after bitkit-core has recorded that its payment started. If the record shows the payment may already have started, the invoice is not paid again and the swap resolves through the updates stream. Because the invoice settles only once the claim confirms, a swap payment normally ends on the pending screen.

## Dev tools

The dev Swaps screen shows the provider's connection state and lets the user reconnect or disconnect it. It also lists the delivery state of the Pubky records the bridge publishes, with a retry for paused deliveries.

## Channel provider seam

Channel orders go through `ChannelProviderRepo`, which picks Blocktank today. A connected Pubky swap provider will take over channel orders once pubky-swap supports them ([pubky-swap#84](https://github.com/coreyphillips/pubky-swap/issues/84)).

## Local development

Only debug builds can use a bitkit-core that contains the bridge, by passing its Android archive as `bitkitCoreAar`. For example, export `ORG_GRADLE_PROJECT_bitkitCoreAar` with the absolute path of the `.aar` before running Gradle.
