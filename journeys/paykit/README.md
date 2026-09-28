# Paykit journeys

These journeys cover how Paykit is switched on and off as a whole. Paykit is on by default from
2.6.0; the Enable Paykit UI switch in Dev Settings stays so testers can turn it off and back on.

Run them on a fresh install of the dev build. Dev Settings shows under Settings ▸ Advanced because
dev mode is on by default in debug builds. No Pubky identity, contacts or funds are needed.

A wallet updated from 2.5.0 keeps Paykit off only when someone turned the switch off before the
update. That upgrade path needs a 2.5.0 build installed first, which the journey environment does
not provide, so it stays a manual test in the PR that changes it.

Turning the switch off on a wallet without a Pubky profile differs by platform. Android shows the
"Paykit UI disabled" success toast. iOS shows the same title as an error toast whose description
reads `no Pubky session available`, because it tries to unpublish endpoints even when none were
published. `default-on.xml` asserts only the toast title and the switch state. The difference is
raised in [synonymdev/bitkit-ios#818](https://github.com/synonymdev/bitkit-ios/pull/818#issuecomment-5873482932).
