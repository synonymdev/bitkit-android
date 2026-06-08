# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/synonymdev/bitkit-android/compare/v2.3.0...HEAD
[2.3.0]: https://github.com/synonymdev/bitkit-android/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/synonymdev/bitkit-android/compare/v2.1.2...v2.2.0
