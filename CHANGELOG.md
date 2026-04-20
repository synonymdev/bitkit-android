# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Surface underlying cause class of keychain load failures in error logs #898

## [2.2.0] - 2026-04-07

### Fixed
- Retouch Primary, Secondary, and Tertiary buttons styling #887
- Avoid msat truncation when paying invoices and LNURL callbacks #879
- Fix ANR on RGS server settings screen caused by catastrophic regex backtracking #880
- Fix crash when returning app to foreground on Receive screen #875
- Show loading state on Spending tab when node is not running #875

### Added
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

[Unreleased]: https://github.com/synonymdev/bitkit-android/compare/v2.2.0...HEAD
[2.2.0]: https://github.com/synonymdev/bitkit-android/compare/v2.1.2...v2.2.0
