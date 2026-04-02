# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Fix crash when returning app to foreground on Receive screen #875
- Show loading state on Spending tab when node is not running #875

### Added
- Show/hide details toggle on send confirmation screen with coin-stack animation #863
- "Send from" payment method switcher (Savings/Spending) for unified BIP21 payments #863
- "Instant" Lightning option on fee rate selection screen for unified payments #863
- Relative invoice expiry formatting on send confirmation screen #863
- Lightning Connections empty state with onboarding screen #857
- Unified PIN management screen (enable/disable/change in one place) #857
- Support entry in drawer menu #857
- Brand endorsement row (Synonym + Tether logos) in Support screen #857
- Reset Widgets and Reset Suggestions Cards options in Widgets settings #857
- Diagonal orange footer background in Support screen #857
- Mnemonic warning text transitions on reveal #857

### Changed
- Custom fee rate defaults now fall back through settings default, slow rate, then 1 #863
- Sanity warnings reset when amount or payment method changes #863
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

[Unreleased]: https://github.com/synonymdev/bitkit-android/compare/v2.1.2...HEAD
