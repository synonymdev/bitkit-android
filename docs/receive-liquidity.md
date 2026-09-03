# Receive Liquidity Behavior

This document describes how the receive flow decides whether to show a normal Lightning invoice or route the user into CJIT liquidity setup.

## Cases

- Opening the Receive sheet:
  - A new Receive sheet session starts from a fresh tab state.
  - If Auto is available, the default tab is Auto.
  - If Auto is unavailable, the default tab is Savings.
  - Temporary receive-session state, such as selected tab, nested navigation, pending CJIT details, and CJIT invoice QR state, must not survive closing and reopening the Receive sheet.

- Editing from Savings or Auto:
  - Editing sets the amount for the receive request.
  - If the edited amount can be received over Lightning, the regenerated Spending invoice also includes that amount.
  - If the edited amount cannot be received over Lightning, Auto falls back to the Savings tab and shows the onchain QR instead of routing to CJIT.
  - The edit flow does not create CJIT or route to CJIT amount entry.

- Lightning receive unavailable because there is no ready channel or inbound liquidity is `0`:
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
  - The direct additional CJIT path must not regenerate the normal receive invoice before creating CJIT.
  - If editing from Spending and the amount is too large for CJIT, or the maximum cannot be calculated, the edit flow routes to CJIT amount entry.
  - The CJIT amount screen enforces the real maximum receivable amount, calculated from `invoiceSat + defaultLspBalance(invoiceSat) <= maxChannelSizeSat`.
  - Editing from Savings or Auto returns to the normal QR with Savings/onchain only.

- Geo-blocked and liquidity is needed:
  - The flow routes to the CJIT geo-block screen.
  - No CJIT invoice is created.

## Invariants

- Auto tab availability and default tab selection are based on whether a normal Lightning invoice can be created for the current receive amount.
- Ready channels alone do not imply Auto availability; fixed receive amounts must also fit within inbound liquidity.
- CJIT min and max limits are only needed when a Spending-origin edit needs additional inbound liquidity and the user is not geo-blocked.
