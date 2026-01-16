# Untranslated Strings Tracker

This document tracks hardcoded strings in the codebase. Strings in dev-only screens do not need translation.

## Dev-Only Strings (No Translation Needed)

These screens are only accessible in development builds and contain hardcoded strings that don't need localization.

### DevSettingsScreen.kt
| Line | String |
|------|--------|
| 52 | Fee Settings |
| 53 | Channel Orders |
| 54 | LDK Debug |
| 56 | LOGS |
| 57 | Logs |
| 59 | Export Logs |
| 66 | REGTEST |
| 68 | Blocktank Regtest |
| 71 | APP CACHE |
| 74 | Reset Settings State |
| 77 | Settings state reset |
| 81 | Reset All Activities |
| 84 | Activities removed |
| 88 | Reset Backup State |
| 91 | Backup state reset |
| 95 | Reset Widgets State |
| 98 | Widgets state reset |
| 102 | Refresh Currency Rates |
| 105 | Currency rates refreshed |
| 109 | Reset App Database |
| 112 | Database state reset |
| 116 | Reset Blocktank State |
| 119 | Blocktank state reset |
| 123 | Reset Cache Store |
| 126 | Cache store reset |
| 130 | Wipe App |
| 133 | Wallet wiped |
| 137 | DEBUG |
| 140 | Generate Test Activities |
| 144 | Generated $count test activities |
| 148 | Fake New BG Receive |
| 151 | Restart app to see the payment received sheet |
| 155 | Open Channel To Trusted Peer |
| 161 | NOTIFICATIONS |
| 164 | Register For LSP Notifications |
| 170 | Test LSP Notification |

### LdkDebugScreen.kt
| Line | String |
|------|--------|
| 96 | LDK Debug |
| 105 | ADD PEER |
| 109 | pubkey@host:port |
| 120 | Add Peer |
| 127 | Paste & Add |
| 135 | NETWORK GRAPH |
| 137 | Log Graph Info |
| 149 | Export to File |
| 160 | VSS |
| 162 | List Keys |
| 164 | found |
| 191 | Delete key |
| 205 | Delete All |
| 211 | NODE |
| 213 | Restart |
| 225 | Delete All VSS Keys? |
| 226 | This will permanently delete all... |
| 227 | Delete All |

### BlocktankRegtestScreen.kt
| Line | String |
|------|--------|
| 57 | Blocktank Regtest |
| 81 | These actions are executed on the staging Blocktank server node. |
| 84 | DEPOSIT |
| 97 | Amount (sats) |
| 104 | Depositing... / Make Deposit |
| 116 | Success |
| 117 | Deposit successful. TxID: ... |
| 123 | Failed to deposit |
| 136 | MINING |
| 146 | Block Count |
| 180 | Mining... / Mine Blocks |
| 162 | Success |
| 163 | Successfully mined $count blocks |
| 169 | Failed to mine |
| 185 | LIGHTNING PAYMENT |
| 189 | Invoice |
| 197 | Amount (optional, sats) |
| 204 | Pay Invoice |
| 215 | Success |
| 216 | Payment successful. ID: ... |
| 222 | Failed to pay invoice from LND |
| 232 | CHANNEL CLOSE |
| 236 | Funding TxID |
| 244 | Vout |
| 253 | Force Close After (seconds) |
| 260 | Close Channel |
| 279 | Success |
| 280 | Channel closed. Closing TxID: ... |

### LogsScreen.kt
- All strings are technical log display (no localization needed)

### ChannelOrdersScreen.kt
- All strings are technical channel order data (no localization needed)

## Preview Functions

Hardcoded strings in `@Preview` functions throughout the codebase do not need translation as they are only visible in Android Studio previews, not to end users.

## Borderline Cases

| File | Line | String | Notes |
|------|------|--------|-------|
| NotificationPreview.kt | 63 | 3m ago | Placeholder in notification mockup |
| BackgroundPaymentsSettings.kt | 111 | ₿ 21 000 | Example amount in preview |
