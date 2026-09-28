# Paykit journeys

These journeys cover how Paykit is switched on and off as a whole. Paykit is on by default from
2.6.0; the Enable Paykit UI switch in Dev Settings stays so testers can turn it off and back on.

Run them on a fresh install of the dev build. Dev Settings shows under Settings ▸ Advanced because
dev mode is on by default in debug builds. No Pubky identity, contacts or funds are needed.

A wallet updated from 2.5.0 keeps Paykit off only when someone turned the switch off before the
update. That upgrade path needs a 2.5.0 build installed first, which the journey environment does
not provide, so it stays a manual test in the PR that changes it.
