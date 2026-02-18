# Multi-Address Types

Users can select and monitor different Bitcoin address formats (Legacy/P2PKH, Nested SegWit/P2SH-P2WPKH, Native SegWit/P2WPKH, Taproot/P2TR). The implementation affects receive addresses, balance aggregation, channel funding, and channel closure.

## Features

- **Primary address type**: The address format used when generating new receive addresses (Legacy, Nested SegWit, Native SegWit, Taproot).
- **Monitoring**: Which address types to include in balance and channel funding. Legacy is excluded from channel funding per design.

## Settings

Stored in `SettingsStore`:

- `selectedAddressType`: Primary receive address type (`"legacy"`, `"nestedSegwit"`, `"nativeSegwit"`, `"taproot"`).
- `addressTypesToMonitor`: List of address types to include in balance aggregation and channel funding.

## Balance and Channel Funding

- **Channel fundable balance**: Sum of spendable sats for selected + monitored types, excluding legacy. Used in FundingScreen for the "Transfer" button availability.
- **Total onchain balance**: Aggregate across all monitored types (from ldk-node when available).
- When per-type balance API is unavailable, channel fundable balance falls back to aggregate spendable.

## Address Type Preference Screen

- **Primary selection**: Radio list of address types. Change triggers node restart with rollback on failure.
- **Monitoring toggles**: Enable/disable monitoring per type. Disabling requires balance to be zero; balance check must succeed.
- **Loading**: 60s timeout with toast on timeout or failure.

## Restart and Recovery

- Changing address type or monitoring requires node restart.
- **Rollback on failure**: Previous settings restored if restart fails.
- **Mutual exclusion**: Only one address type change in progress at a time.

## Testing Matrix

| Flow          | Legacy | Nested SegWit | Native SegWit | Taproot                     |
| ------------- | ------ | ------------- | ------------- | --------------------------- |
| Receive       | ✓      | ✓             | ✓             | ✓                           |
| Send          | ✓      | ✓             | ✓             | ✓                           |
| Channel open  | -      | ✓             | ✓             | ✓                           |
| Channel close | -      | Native SegWit | Native SegWit | Taproot if selected at open |
| CPFP/RBF      | ✓      | ✓             | ✓             | ✓                           |

Manual verification recommended for each address type and combinations.
