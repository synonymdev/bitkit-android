# Receive Liquidity Behavior

This document describes how the receive flow decides whether to show a normal Lightning invoice or send the user into CJIT liquidity setup.

## Cases

- Opening Receive:
  - A new receive session starts from a fresh tab state.
  - If Auto is available, the default tab is Auto.
  - If Auto is unavailable, the default tab is Savings.
  - Temporary receive-session state, such as the selected tab, nested navigation, pending CJIT details, and CJIT invoice QR state, must not
    survive closing and reopening Receive.

- Editing from Savings or Auto:
  - Editing sets the amount for the receive request.
  - If the edited amount can be received over Lightning, the regenerated Spending invoice also includes that amount.
  - If the edited amount cannot be received over Lightning, Auto falls back to the Savings tab and shows the onchain QR instead of routing to CJIT.
  - The edit flow does not create CJIT or route to CJIT amount entry.

- Editing from a hardware receive tab:
  - Editing sets the amount for the hardware/onchain receive request.
  - Returning from the edit flow preserves the hardware receive tab when the edit originated there.
  - Editing from Savings or Auto while a hardware wallet is available still returns to the source tab, not the hardware tab.

- Lightning receive unavailable because there is no ready channel or ready inbound liquidity is `0`:
  - No Lightning invoice is created.
  - The normal QR remains Savings/onchain only.
  - The Spending tab shows CJIT onboarding.
  - Tapping receive spending routes to CJIT amount entry, or the CJIT geo-block screen when geo-blocked.
  - Editing from Savings or Auto updates the receive amount and returns to the normal QR; it does not create or route to CJIT.
  - When a channel already exists, later CJIT confirmation and learn-more screens use additional-liquidity copy.

- Ready channel, inbound liquidity greater than `0`, zero/variable amount:
  - A Lightning invoice is allowed.
  - A zero/variable Lightning invoice is allowed when inbound liquidity is greater than `0`, even though the sender could later choose an amount above the available inbound capacity.

- Ready channel, fixed amount less than or equal to inbound liquidity:
  - A normal BOLT11 invoice is created.
  - The unified QR includes Lightning.
  - The Spending tab shows the normal Lightning invoice.

- Ready channel, fixed amount greater than inbound liquidity but below CJIT minimum:
  - A normal Lightning invoice is not shown.
  - Editing from Spending routes to CJIT amount entry.
  - The user must choose at least the minimum CJIT amount.
  - Editing from Savings or Auto returns to the normal QR with Savings/onchain only.

- Ready channel, fixed amount greater than inbound liquidity and at or above CJIT minimum:
  - If editing from Spending and the amount can be backed by a CJIT channel without exceeding Blocktank's maximum channel size, the edit flow creates additional CJIT.
  - The user gets CJIT confirmation and then a CJIT Lightning invoice QR.
  - The CJIT Lightning invoice is an invoice to the LSP and must be shown as Spending-only, not as Auto/unified receive.
  - Editing from a CJIT Lightning invoice QR must replace the previously displayed CJIT invoice before showing the updated receive result,
    because the previous LSP invoice is immutable.
  - The direct additional CJIT path must not regenerate the normal receive invoice before creating CJIT.
  - If editing from Spending and the amount is too large for CJIT, or the maximum cannot be calculated, the edit flow routes to CJIT amount entry.
  - The CJIT amount screen enforces the real maximum receivable amount, calculated from `invoiceSat + defaultLspBalance(invoiceSat) <= maxChannelSizeSat`.
  - If Blocktank rejects additional CJIT because the node is already at its total capacity limit, the app explains that additional spending capacity is unavailable instead of showing the per-channel maximum.
  - Editing from Savings or Auto returns to the normal QR with Savings/onchain only.

- Geo-blocked and liquidity is needed:
  - The flow routes to the CJIT geo-block screen.
  - No CJIT invoice is created.

## Invariants

- Auto tab availability and default tab selection are based on whether a normal Lightning invoice can be created for the current receive amount.
- Ready channels alone do not imply Auto availability; fixed receive amounts must also fit within ready inbound liquidity.
- CJIT min and max limits are only needed when a Spending-origin edit needs additional inbound liquidity and the user is not geo-blocked.
- Before displaying a CJIT confirmation, the app must reject quotes where `feeSat >= invoiceSat` or
  `channelSizeSat < invoiceSat - feeSat`. Invalid quotes must show a user-facing error and must never produce a negative receive amount.

## Platform Differences

No intentional platform differences are currently specified.
