# Activity: list, filters, detail, explorer, tags, contacts, boost

Transaction history of the Bitkit wallet and watch-only hardware wallets: home list, All Activity with search, tabs, tag and date filters, detail, block-explorer detail, tags, contact assignment and Boost (CPFP/RBF). Hardware rows are also in `hardware-wallet.md`.

## What it does
- One list merges Lightning and on-chain items of the default wallet with hardware-wallet items (`ActivityListViewModel`, `walletId = null` is global). Home shows the newest 4 (`SIZE_LATEST`).
- All Activity: search text (300 ms debounce), tabs `ALL`/`SENT`/`RECEIVED`/`OTHER`, tag filter, date range. `SENT` and `RECEIVED` exclude transfers; `OTHER` is transfers only. Filters are cleared on every entry from home, Savings or Spending (`navigateToAllActivity(clearFilters)`).
- Hardware rows are filtered in memory: they match search only by raw id, are dropped when a tag filter is active and on the `OTHER` tab.
- A sent tx replaced by RBF (`doesExist == false`, id in another tx's `boostTxIds`) is hidden (`filterOutReplacedSentTransactions`).
- Detail: status (Lightning pending/successful/failed; on-chain confirming/boosting/confirmed/removed/transfer), amount, fee, date, tags, contact, invoice note, buttons Assign/Detach, Tag, Boost, Explore, Connection (transfers).
- Explore: `TXID`, inputs and outputs from Electrum, boost tx ids (`RBFBoosted` for sent, `CPFPBoosted` for received), Lightning preimage, hash and invoice.
- Boost: received unconfirmed tx gets CPFP (`lightningRepo.accelerateByCpfp`), sent unconfirmed tx gets RBF (`bumpFeeByRbf`). After success the activity is marked `isBoosted` and `boostTxIds` extended; `PendingBoostActivity` (`CacheStore`) re-applies it after the next sync (`boostPendingActivities`).
- Tags: per activity, kept as core tags (hardware wallets: pre-activity metadata). Tag text is capped at `Defaults.TAG_MAX_LENGTH` = 20 and line breaks become spaces (`String.sanitizeTag`). Last used tags live in `SettingsStore.lastUsedTags`.
- Pending payments: Lightning rows with `PaymentState.PENDING` show title "Pending"; no separate screen.

## How a user reaches it
- Home: `ActivityShort-<n>` rows and `ActivityShowAll` (`ActivityListSimple`); Savings and Spending screens list their own type and a Show All footer. Route `Routes.AllActivity`.
- All Activity: rows `Activity-<n>` where `<n>` is the index in the grouped list including date headers (first row is usually `Activity-1`); list container `ActivityList`; tabs `Tab-all`, `Tab-sent`, `Tab-received`, `Tab-other` (enum name, locale independent); filter icons `TagsPrompt`, `DatePicker`; the search input has no testTag.
- Tag filter: `Sheet.ActivityTagSelector` (`TagSelectorSheet`); chips `Tag-<tag>`, `Tag-<tag>-delete`.
- Date filter: `Sheet.ActivityDateRangeSelector`: `PrevMonth`, `NextMonth`, `CalendarGrid`, `CalendarSwipeArea`, `Day-<n>`, `Today` (replaces `Day-<n>` on the current day), `CalendarClearButton`, `CalendarApplyButton`.
- Detail (`Routes.ActivityDetail(id, walletId)`): `ActivityAmount`, `ActivityFee`, `ActivityTags`, `InvoiceNote`, `ActivityAssignedContact`, `ActivityTag`, `BoostButton` | `BoostedButton` | `BoostDisabled`, `ActivityTxDetails` (Explore), `ChannelButton`; status `StatusConfirmed`, `StatusBoosting`, `StatusRemoved`, `StatusTransfer` (no tag for plain "confirming" or for Lightning); icons `BoostingIcon`, `TransferIcon`, `HardwareActivityIcon`, `ActivityIcon`. The Assign/Detach button has no testTag (shown when Paykit UI is on).
- Add tag: `ActivityTag` opens `ActivityAddTagSheet`: `TagInput`, `ActivityTagsSubmit`.
- Assign contact: `Routes.ActivityAssignContact(id)` screen tag `AssignActivityContactScreen`, rows `AssignActivityContact_<publicKey>`.
- Boost sheet: container `RBFBoost` or `CPFPBoost`, `CustomFeeButton`, `Plus`, `Minus`, `RecommendedFeeButton`, `GRAB`; toasts carry `BoostSuccessToast`, `BoostFailureToast`.
- Tag settings: Settings > General `TagsSettings` (row only when `tagCount > 0`) > `TagsSettingsScreen`; tapping a tag deletes it from the last-used list.

## Code
- Screens in `app/src/main/java/to/bitkit/ui/screens/wallets/activity/`: `AllActivityScreen`, `ActivityDetailScreen`, `ActivityExploreScreen`, `ActivityAssignContactScreen`, `DateRangeSelectorSheet`, `TagSelectorSheet`; `components/` (`ActivityListGrouped`, `ActivityListSimple`, `ActivityListFilter`, `ActivityRow`, `ActivityIcon`, `ActivityAddTagSheet`, `CustomTabRowWithSpacing`). Also `ui/sheets/BoostTransactionSheet.kt`, `ui/settings/general/TagsSettingsScreen.kt`, `ui/screens/wallets/send/AddTagScreen.kt` (shared tag input), `ui/components/ActivityBanner.kt`, `IncomingTransfer.kt`.
- Routes (`ui/ContentView.kt`): `AllActivity`, `ActivityDetail`, `ActivityExplore`, `ActivityAssignContact`, `TagsSettings`; sheets `ActivityTagSelector`, `ActivityDateRangeSelector`. Boost sheet and add-tag sheet are shown inside the detail screen (`boostSheetVisible`), not through `Sheet`.
- State: `viewmodels/ActivityListViewModel.kt`, `viewmodels/ActivityDetailViewModel.kt`, `ui/sheets/BoostTransactionViewModel.kt`, `viewmodels/TagsViewModel.kt`, `SettingsViewModel`.
- Data: `repositories/ActivityRepo.kt` (sync, filters, tags, contacts, boost bookkeeping, restore), `services/CoreService` activity service, `repositories/PreActivityMetadataRepo.kt`, `data/dto/PendingBoostActivity.kt`, `repositories/HwWalletRepo.kt` (hardware rows).
- Boost rules: button enabled only for on-chain, non-hardware, not a CPFP child, `doesExist`, unconfirmed, not already boosted-and-completed, not a transfer, value > 0 (`shouldEnableBoostButton`). Fee rate steps of 1 sat/vB, max 100 (`MAX_FEE_RATE`), total fee max 50% of the tx value; RBF minimum is original rate + 2; CPFP rate from `calculateCpfpFeeRate`. Hitting a limit sends `OnMinFee`/`OnMaxFee` (error toasts).

## How to drive it
- Journeys: `journeys/activity/date-range-rapid-month-taps.xml` (8 rapid `NextMonth` taps via a monkey script; month label and grid visibility from screenshots); `journeys/tags/activity-tag-length-cap.xml` (input stops at 20 chars, chip is one line). Hardware activity: `journeys/hardware-wallet/activity-blue-icons.xml`, `activity-detail-hw-tags.xml` (see `hardware-wallet.md`).
- E2E `bitkit-e2e-tests/test/specs/boost.e2e.ts`: `@boost_1` CPFP and `@boost_2` RBF, both with wipe and restore, local shard `onchain_boost_receive_widgets` (`BACKEND=local`; `bitkit-e2e-tests/AGENTS.md` notes `@boost` needs an exclusive miner). `multiaddress.e2e.ts` `@multi_address_3` also boosts (local shard `multi_address_local`).
- Filters, tabs, tags, calendar: `onchain.e2e.ts` `@onchain_2` (same shard), `lightning.e2e.ts` `@lightning_1` (shard `lightning_security`), tag add `backup.e2e.ts` `@backup_1` (shard `onboarding_backup_numberpad`), tag removal `settings.e2e.ts` `@settings_04` (shard `settings`).
- Preconditions: `receiveOnchainFunds({ blocksToMine: 0 })` keeps a deposit unconfirmed for CPFP; RBF sends to `getExternalAddress()`. Journeys need at least one activity item and `./lsp` for funds.
- Unit: `app/src/test/java/to/bitkit/viewmodels/ActivityListViewModelTest.kt` (5, hardware merge), `repositories/ActivityRepoTest.kt` (67), `repositories/ActivityDetailViewModelTest.kt`, `ui/sheets/BoostTransactionViewModelTest.kt` (14), `viewmodels/TagsViewModelTest.kt` (10); androidTest `BoostTransactionSheetTest.kt`, `DateRangeSelectorContentTest.kt`, `TagButtonTest.kt`, `AddTagContentTest.kt`.

## What proves it
- CPFP: toast `BoostSuccessToast`; `BoostingIcon` on home; `ActivityShort-0` "Boost Fee" and "-", `ActivityShort-1` the original "+"; original detail `BoostedButton` and `StatusBoosting`; `CPFPBoosted` id equals the new `TXID`; after a block `StatusConfirmed`; same after wipe and restore.
- RBF: `RBFBoost` sheet; new `ActivityFee` higher, `TXID` differs, explorer `RBFBoosted`.
- Filters: `Activity-1..n` present or absent per tab; `Tag-stag` leaves one row; next-month calendar range leaves none, clear or today restores all.
- Tag add: chip in `ActivityTags` (restored after backup). 20-char cap: chip tag equals the 20-character input.

## Not covered by tests
- Search text, `Tab-other` with transfer rows and hardware rows in filters: only unit tests for the hardware merge.
- Assign and detach contact UI (`AssignActivityContact_*`, no tag on the button): no journey or e2e; repo logic in `ActivityRepoTest`.
- `BoostFailureToast`, min/max fee toasts, the 50% max-fee rule, `BoostDisabled` reasons other than confirmed receive: unit tests only (`BoostTransactionViewModelTest`).
- Removed (`StatusRemoved`) state, RBF replacement hiding in the list, Lightning detail `FAILED`/`PENDING`: no UI test found.
- Explorer "open in block explorer" action and Lightning explorer fields: none found.
- Date range with only a start day, and `CalendarSwipeArea` outside the rapid-tap journey: none found.

## Gotchas
- `journeys/README.md`: toasts never reach `android layout`, so a journey needs a screenshot taken right away; e2e reads them through `waitForToast('BoostSuccessToast')`.
- `Activity-<n>` counts date headers; `ActivityShort-<n>` does not.
- The calendar's `Today` tag replaces `Day-<n>` on the current day, so `Day-1` assertions fail when the 1st is today (journey note).
- Boost is disabled for transfers (`!activity.isTransfer`) and for every hardware row.
- `@onchain_2` has the receive-tag filter commented out (bitkit-android issue 322); only the send tag runs.
- `adb shell input text` drops characters; type tags in 8-character chunks (tag journey).
