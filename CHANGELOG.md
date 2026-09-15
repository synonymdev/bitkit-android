# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.5.0] - 2026-09-10

### Added
- Added receiver-specific Paykit contact support for Bitkit wallet and server integrations. #1066
- Bitkit now creates, names, backs up, and manages separate watch-only Bitcoin accounts and securely delivers signed setup claims to Paykit servers. #1084
- Incoming Paykit payment requests can now be reviewed and approved through the existing payment flow. #1098
- Developers running their own build can now open supported screens directly with `bitkit://screen/...` links while dev mode is enabled. #1119
- Passphrase-protected (hidden) Trezor wallets can now be paired from the connect flow, each appearing as its own watch-only balance with its own label, activity and removal, and asking for its passphrase again when a transfer needs signing. #1142
- Incoming Paykit payment requests now stay discoverable until handled, and users can send new private requests to connected contacts from the invoice flow. #1172
- The name you give a hardware wallet is now included in your backup and comes back when you pair the device again, and removing a hardware wallet asks first whether to keep its name and tags in your backup. #1173
- Payments made from incoming private payment requests now send a payment proof back to the requester. #1178
- Bitkit can now review, manage, and pay recurring payment requests from Paykit contacts, with on-chain fees shown before confirming the first payment and unresolved payments protected from accidental retries. #1186
- Added on-chain send support for paired Trezor wallets, including transaction approval on the device. #1187
- Added a Trezor Receive tab with fast watch-only addresses and on-device verification. #1189

### Changed
- Added support for tags and transaction details to hardware wallet activity. #1044
- Updated Pubky profiles, contacts, and Paykit settings to match the latest designs and simplified contact payments. #1097
- Send now keeps the funding-source button, fee label, and swipe-to-confirm track stable while switching sources, continuing from amount, or refreshing fees. #1195

### Fixed
- Receive requests now refresh reliably after a payment is completed. #1086
- The Show Details button on the Receive screen is now available while the Lightning node is still starting up, so you can view and copy your on-chain address right away. #1090
- Savings transfers to spending now use a faster on-chain fee rate, reserve mining fees when sizing max transfers, and only drain the wallet when leftover change would be dust or a retry can still fully fund the order. #1091
- Fixed profile deletion being blocked when private Paykit contact cleanup could not complete. #1108
- Activity timestamps now follow the device's 12/24-hour time setting instead of always using a 12-hour clock. #1111
- App Status and Backup timestamps now follow the device language instead of always using the US date format. #1124
- Fixed Paykit Server authorization to require both public and private payment capabilities. #1131
- Restored the transfer label on hardware wallet transactions after removing and re-adding a device. #1133
- Improved Lightning send failure recovery with clearer messages and a retry action that refreshes payment routing. #1140
- Fixed delayed private payment requests after adding a Paykit contact or returning to the app. #1141
- Tags you add to hardware wallet activity are now included in your backup, and older backups are upgraded automatically the first time you restore them. #1163
- Error toasts are now visible on top of modal bottom sheets, including the hardware transfer passphrase sheet. #1165
- The maximum Savings to Spending transfer amount now always leaves room for its own service fee, so transferring your full balance no longer fails with an insufficient funds error. #1179
- The advanced transfer screen now offers a maximum receiving capacity your balance can actually pay for, instead of one that fails later on the confirmation screen. #1180
- Hardware-wallet payments now send payment proofs for incoming Paykit requests. #1199
- Receiving over Lightning now correctly falls back to Savings or additional liquidity setup when the requested amount exceeds available inbound capacity. #1222
- The app no longer aborts when stopping the Lightning node, and 32-bit devices can open the wallet again. #1223
- A hardware wallet payment whose broadcast fails no longer leaves the send screen and sheet stuck, and Bitkit now says the payment could not be confirmed instead of reporting a generic connection problem. #1230
- LSP orders now include a wallet-owned address for on-chain refunds. #1235

### Security
- QuickPay stays PIN-free under a configurable daily spend limit; once that limit is reached, payments open Confirm instead. #1159
- Connecting a custom Electrum server no longer crashes Bitkit if the server sends an oversized response. #1198
- Added app-scoped Pubky authorization and secure sign-out, and fixed missing payment requests in history. #1200

## [2.4.1] - 2026-08-21

### Changed
- Bitkit now keeps the Lightning node running a little longer when you briefly leave the app, so quick trips to another app no longer reload the wallet on return. #1146

### Fixed
- Lightning node teardown now releases native resources deterministically and no longer restarts the node when the app is only briefly backgrounded. #1100
- Fixed native library compatibility on Android devices using 16 KB memory pages. #1107
- Lightning node shutdown and peer persistence now complete without native crashes or app hangs. #1122
- Fixed Pubky authorization links opening Bitkit when the feature is unavailable or no local identity can approve them. #1162
- Fixed the wallet backup failing repeatedly when Paykit state could not be read. #1092
- Hardened background task error handling to prevent rare crashes. #1094

### Security
- Lightning no longer automatically starts from outdated channel monitor data after a storage mismatch. #1155
- Wallet backups no longer fall back to unauthenticated VSS when LNURL-auth is missing. #1156
- Shop checkout only accepts Bitrefill payment requests, and payment links wait until the wallet is unlocked. #1158
- Wallet backups now use VSS 0.5.23, which rejects unauthenticated encryption. #1164

## [2.4.0] - 2026-07-15

### Added
- Added a dedicated detail screen for paired hardware wallets, showing balances, transaction history, and a remove action. #1022
- Added a Hardware Wallets settings screen to view and remove paired devices, reachable from Settings under General › Payments. #1032
- Connect a Trezor hardware wallet from the home suggestion card or Hardware Wallets settings to watch its balance. #1033
- Transfer funds from a paired Trezor hardware wallet to your spending balance, with safer fee handling and confirmation tracking for device-signed funding transactions. #1039
- Background payments now include a "Keep Bitkit active in background" option for more reliable payments, and payment notifications always show the amount received. #1053
- Added the ability to rename saved hardware wallets from Settings. #1057
- Trezor hardware wallet support with improved connection reliability, native SegWit balance tracking, and transfer to spending. #1062
- Show paired Trezor hardware wallet balances and activity on the home screen, with sheets to enter pairing code and notify incoming hardware wallet transactions. #999

### Changed
- Update FX rates endpoint to no longer use old Blocktank service. #1072

### Fixed
- Hardware wallet transfers to spending now use a faster on-chain fee rate so funding confirms more reliably. #1089
- Connection Details now shows the short channel ID for Lightning connections instead of the long internal channel ID. #1002
- The system notification permission dialog is now only requested when you tap Enable on the background payments prompt, instead of appearing automatically on every app open. #1004
- Transfer to Spending now fills in the maximum transferable amount when you enter a value above the limit, instead of only showing an error. #1013
- Bitkit now handles more unexpected native on-chain wallet sync failures without crashing. #1015
- Re-adding the Suggestions widget now restores the default suggestion cards when all of them had been dismissed. #1016
- Transferring to your spending balance now reliably shows the "Spending Balance Ready" confirmation instead of sometimes being mistaken for an incoming payment when an unused instant-payment invoice is still pending. #1017
- Recovery mode now has a Reset Network Graph option that re-downloads the Lightning network graph to fix "route not found" errors. #1020
- Error toasts now use the brand orange color to match the design prototype. #1023
- Channel close and transfer activities, including force closes, now offer an Explore button so the on-chain transaction ID and block explorer details are accessible. #1037
- Spending balances no longer briefly include cooperatively closed channel funds after opening a new channel. #1043
- Tapping an NFC tag now opens the gift claim and other deep links instead of being ignored, and the NFC intent filter no longer declares the unused BROWSABLE category that triggered a Play Console deep link warning. #1054
- Pending transfer funds now stay reflected in the total balance without temporarily inflating it while moving between savings and spending. #1058
- Fixed a raw technical error appearing when a locked Trezor blocks connecting or signing; Bitkit now asks you to unlock the device and retries automatically. #1065
- Improved Trezor pairing, Bluetooth recovery, connection and unlock guidance, transfer cancellation, and signed-broadcast retries. #1067
- Hardware wallet transfer sign screens now show the on-chain mining fee and total outflow that matches Trezor confirmation. #1071
- Tapping Connect on the Electrum server settings screen no longer freezes the app when the host field contains a long or malformed value. #1077
- Fixed a crash that could occur when showing the backup-failure notification in some languages. #1080
- Instant-channel payment notifications now appear once with a correctly formatted amount, and opening a regular channel no longer shows a misleading "payment received" notification. #787
- The amount number pad now caps entry at the available maximum on the send, LNURL, transfer, and channel-funding screens, stays usable at a zero balance, and lets you delete down from an over-cap amount. #908
- Home-screen widgets no longer render too tall on Android 10 and 11, matching the size shown on newer Android versions. #994
- Fixed private contact payment preferences so they only show confirmed endpoint publication or removal state. #997





## [2.3.2] - 2026-07-14

### Fixed
- Improved Lightning payment route selection reliability. #1052

## [2.3.1] - 2026-06-26

### Fixed
- Improved LNURL-pay invoice validation. #1048
- Improved LNURL-pay payment handling. #1051

## [2.3.0] - 2026-06-05

### Added
- Added Trezor hardware wallet support for connecting devices, signing messages, and managing on-chain transactions. #792
- Connection issues overlay with connectivity fixes across Send, Receive, and Transfer flows #878
- Home screen widgets foundation with Glance, including price widget as the first implementation #895
- Return to Bitkit after Pubky Ring approval, cancellation, or error callbacks #917
- Headlines home screen widget with v61 wide and compact layouts, including redesigned in-app preview and edit screens #919
- Bitcoin Blocks home screen widget with v61 wide and compact layouts, including redesigned in-app preview and edit screens #922
- Support public Paykit contact payments. #924
- Bitcoin Facts home screen widget with v61 wide and compact layouts, including redesigned in-app facts card and preview screen #926
- Bitcoin Weather home screen widget with v61 wide and compact layouts, including redesigned in-app weather card, preview, and edit screens #927
- Added private Paykit contact payments with dedicated contact endpoints, rotation, cleanup, and restore-safe address reservations. #936
- Added contact payment flows, activity contact attribution, and payment preference controls for private payments. #945
- Added BTCPay wallet connection support for sharing Bitcoin receive descriptors. #961
- Added a Legacy Recovery option in developer settings to help recover funds from affected legacy channel closes. #974
- Home widgets can now be resized between compact and wide from the preview sheet, with a redesigned two-column grid, inline edit mode, and an interactive compact calculator. #985

### Changed
- Redesign price widget with v61 wide and compact layouts, new preview and edit screens, and tap-to-edit behavior #914
- Activity, Shop and Settings now keep their tabs pinned with a drop shadow as content scrolls behind them, the Add Widget button shows its icon, and the Shop list has more bottom spacing. #916
- Redesigned the Bitcoin Calculator widget to v61 design and replaced the OS keyboard with a dark-themed in-app numpad #942
- Hide experimental Paykit profile, contacts, and contact payment controls behind a developer setting. #954
- The add widget experience now opens as a bottom-sheet flow with in-sheet previews instead of full-screen picker pages. #972
- Improve Pubky profile restore, contact editing, and contact routing flows #905

### Fixed
- Fix Spending and Savings screens scrolling behind top bar and add gradient fade effect #892
- Align currency settings and calculator widget behavior with iOS #884
- Align onboarding slides and Create Wallet screen image size, spacing, and dots layout with iOS #904
- Align tab colors, Show details button, notifications bell figure, and home activity count with iOS #907
- Payment QR scans now route reliably and avoid unnecessary delays when Lightning channels are unavailable. #925
- Fix gift card flow showing false-positive confetti when the LSP payment fails, and re-opening unexpectedly after an app language change. #929
- Improved public contact payment flows for manual Pubky entry, add-contact payments, and RBF activity display. #931
- Fix several OS widget issues including an intermittent crash when removing or cancelling a home screen widget, ordering of widget options, and the color of disabled checkboxes in widget configuration screens. #935
- Improved OS widgets so previews, settings, currency display, and OS-home interactions match v61 design iteration. #952
- Improved logs, support diagnostics and channel peer recovery after wallet restore. #969
- The Support page now shows the current copyright year automatically. #971
- Android home-screen widgets now refresh after unlocking and keep retrying with backoff while connectivity is still coming back. #978
- Improved BTCPay setup link handling so Bitkit opens supported connection links reliably and shows clearer setup errors. #979
- Bitkit no longer crashes when Android stops the background Lightning node service. #987
- Bitkit now handles unexpected native on-chain lookup failures without crashing. #989
- New widgets now open on compact size in the preview carousel, matching iOS, and the add-widgets list keeps its scroll position when navigating back. #990
- Fix probe results and add keysend probes #920
- Align top bar back arrow and passphrase input cursor/placeholder with iOS #906
- Polish Terms of Use screen padding to match iOS #903

## [2.2.0] - 2026-04-07

### Fixed
- Retouch Primary, Secondary, and Tertiary buttons styling #887
- Avoid msat truncation when paying invoices and LNURL callbacks #879
- Fix ANR on RGS server settings screen caused by catastrophic regex backtracking #880
- Fix crash when returning app to foreground on Receive screen #875
- Show loading state on Spending tab when node is not running #875

### Added
- Transfer from Savings button on empty Spending screen when savings balance exists #882
- Pubky profile onboarding with contact sync, import, and editing #824
- Lightning Connections empty state with onboarding screen #857
- Unified PIN management screen (enable/disable/change in one place) #857
- Support entry in drawer menu #857
- Brand endorsement row (Synonym + Tether logos) in Support screen #857
- Reset Widgets and Reset Suggestions Cards options in Widgets settings #857
- Diagonal orange footer background in Support screen #857
- Mnemonic warning text transitions on reveal #857

### Changed
- Show end of address on Receive Bitcoin screen using middle ellipsis truncation #886
- Update funding screen: replace Advanced with Manual Setup, fix Use Other Wallet navigation to open amount entry, and add Fund Wallet button to no-funds dialog #885
- Updated design of the success screen in the manual channel setup flow #883
- Unified send flow with payment method switcher, details toggle, Lightning support for BIP21 payments, and improved fee rate defaults #863
- Settings redesigned with tabbed navigation (General/Security/Advanced) with swipe support #857
- Icons added to all settings rows for faster scanning #857
- Selected values displayed on right side of settings rows #857
- Support screen redesigned with About content merged in #857
- Backup and Reset moved into Security tab #857
- PIN flow reworked into sheet-based enable/disable/change #857
- Social links simplified with Brand tint #857
- Mnemonic warning updated with new copy and red styling #857
- Security title changed from "Security and Privacy" to "Security" #857
- Language model updated to use string resources for "System Settings" #857

### Removed
- About screen (content merged into Support) #857
- Standalone General, Security, and Advanced settings screens (merged into tabs) #857

[Unreleased]: https://github.com/synonymdev/bitkit-android/compare/v2.5.0...HEAD
[2.5.0]: https://github.com/synonymdev/bitkit-android/compare/v2.4.1...v2.5.0
[2.4.1]: https://github.com/synonymdev/bitkit-android/compare/v2.4.0...v2.4.1
[2.4.0]: https://github.com/synonymdev/bitkit-android/compare/v2.3.2...v2.4.0
[2.3.2]: https://github.com/synonymdev/bitkit-android/compare/v2.3.1...v2.3.2
[2.3.1]: https://github.com/synonymdev/bitkit-android/compare/v2.3.0...v2.3.1
[2.3.0]: https://github.com/synonymdev/bitkit-android/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/synonymdev/bitkit-android/compare/v2.1.2...v2.2.0
