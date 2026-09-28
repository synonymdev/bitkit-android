# Paykit journeys

Paykit is on by default from 2.6.0. These journeys check that a new wallet shows it without any
setup. The Enable Paykit UI switch in Dev Settings stays in 2.6.0 as an internal option only, and no
journey relies on it.

Run them on a fresh install of the dev build. No Pubky identity, contacts or funds are needed.

A wallet updated from 2.5.0 keeps Paykit off only when someone turned the switch off before the
update. That upgrade path needs a 2.5.0 build installed first, which the journey environment does
not provide, so it stays a manual test in the PR that changes it.
