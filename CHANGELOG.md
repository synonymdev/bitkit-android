# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Show/hide details toggle on send confirmation screen with coin-stack animation
- "Send from" payment method switcher (Savings/Spending) for unified BIP21 payments
- "Instant" Lightning option on fee rate selection screen for unified payments
- Relative invoice expiry formatting on send confirmation screen

### Changed

- Custom fee rate defaults now fall back through settings default, slow rate, then 1
- Sanity warnings reset when amount or payment method changes

[Unreleased]: https://github.com/synonymdev/bitkit-android/compare/v2.1.2...HEAD
