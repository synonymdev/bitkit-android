# Receive Liquidity Behavior

This document describes how the receive flow decides whether to show a normal Lightning invoice. Blocktank no longer sells
Lightning channels, so the app never creates a CJIT: when receiving needs more inbound capacity than the user has, it shows
the Transfer Unavailable screen, which leads to Manual Setup.

## Cases

- Opening Receive:
  - A new receive session starts from a fresh tab state.
  - If Auto is available, the default tab is Auto.
  - If Auto is unavailable, the default tab is Savings.
  - Temporary receive-session state, such as the selected tab and nested navigation, must not survive closing and
    reopening Receive.

- Editing from Savings or Auto:
  - Editing sets the amount for the receive request.
  - If the edited amount can be received over Lightning, the regenerated Spending invoice also includes that amount.
  - If the edited amount cannot be received over Lightning, Auto falls back to the Savings tab and shows the onchain QR.

- Editing from a hardware receive tab:
  - Editing sets the amount for the hardware/onchain receive request.
  - Returning from the edit flow preserves the hardware receive tab when the edit originated there.
  - Editing from Savings or Auto while a hardware wallet is available still returns to the source tab, not the hardware tab.

- Lightning receive unavailable because there is no ready channel or ready inbound liquidity is `0`:
  - No Lightning invoice is created.
  - The normal QR remains Savings/onchain only.
  - The Spending tab shows the Lightning onboarding view; tapping receive Lightning funds opens the Transfer Unavailable
    screen.
  - Editing from Savings or Auto updates the receive amount and returns to the normal QR.

- Ready channel, inbound liquidity greater than `0`, zero/variable amount:
  - A Lightning invoice is allowed, even though the sender could later choose an amount above the available inbound
    capacity.

- Ready channel, fixed amount less than or equal to inbound liquidity:
  - A normal BOLT11 invoice is created.
  - The unified QR includes Lightning.
  - The Spending tab shows the normal Lightning invoice.

- Ready channel, fixed amount greater than inbound liquidity:
  - A normal Lightning invoice is not shown.
  - Editing from Spending opens the Transfer Unavailable screen.
  - Editing from Savings or Auto returns to the normal QR with Savings/onchain only.

## Invariants

- Auto tab availability and default tab selection are based on whether a normal Lightning invoice can be created for the
  current receive amount.
- Ready channels alone do not imply Auto availability; fixed receive amounts must also fit within ready inbound liquidity.

## Platform Differences

No intentional platform differences are currently specified.
