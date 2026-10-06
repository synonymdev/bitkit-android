# Onchain send

`rbf-replacement-fee-rate.xml` is shared with bitkit-ios under the same name and action sequence.
Use a disposable regtest wallet and a recipient belonging to another wallet. Never use mainnet funds.

Android dev uses staging regtest; iOS E2E builds use the configured regtest backend. Both expose the
journey's identifiers. Both platforms edit custom Boost fees with plus/minus buttons; the journey
only inspects that screen and keeps the recommended rate on both platforms.

Do not inject the recipient with `adb input text`: long strings can be truncated. Manual reviewers
can paste it normally. If an agent cannot enter the recipient through the written route, stop and
report that step rather than substituting a deep link, which would skip the recipient screens.

Until the core fix is released, this journey needs the local core SDK exposing `recordRbfBoost`
and `upsertOnchainActivityPreservingFeeRate`; the published 0.5.18 SDK does not contain those APIs.
