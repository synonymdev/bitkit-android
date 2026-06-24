# Hardware Wallet Journeys

AI-driven UI test journeys for the home-screen hardware wallet features, designed to run
against the deterministic Trezor emulator from `synonymdev/bitkit-docker` — no physical
Trezor required. Journeys follow the `android` CLI journey XML format: natural-language
`<action>` steps evaluated sequentially against the running app; any failed step fails
the journey.

## What the Bridge emulator does and does not simulate

The Bridge transport is HTTP (`TrezorBridgeTransport` → `http://127.0.0.1:21325` through
`adb reverse`), so it bypasses the Android USB stack entirely. Calibrate expectations:

- **Reliably simulated**: the device itself (deterministic seed and label), the full
  wallet protocol (scan, connect, features, xpubs, watchers, signing), and therefore all
  home-screen UI behavior: tiles, balances, activity, indicators, sheets. This includes the
  Connect Hardware flow's Intro → Searching → Found → Paired steps (the Bridge device pairs
  without the inline pair-code step).
- **Partially simulated**: the USB attach → auto-reconnect chain. The OS-level attach
  intent can be injected with `am start -a android.hardware.usb.action.USB_DEVICE_ATTACHED`,
  which drives the in-app reconnect path (MainActivity → AppViewModel → reconnect loop), with
  the Bridge standing in for the transport. A real OS chooser event with a `UsbDevice` extra
  is still needed to verify the "Open with Bitkit" path that opens the Found Device sheet for
  an unpaired Trezor.
- **Not simulated**: kernel/libusbhost behavior, USB enumeration timing, permission
  grants, the OS app picker, BLE runtime/settings recovery, THP one-time pairing code
  (the inline Pair Device step), and passphrase/hidden-wallet selection. Those need a physical
  device or a dedicated emulator scenario; passphrase coverage is tracked in
  synonymdev/bitkit-android#1030.

Journey steps that start with `adb:` are device commands the runner executes verbatim
instead of UI interactions.

## Prerequisites

1. Docker running with the `bitkit-docker` stack up:
   ```sh
   cd ../bitkit-docker && docker compose up -d
   ```
2. Deterministic Trezor User Env started (Bridge on `21325`, T2T1 emulator, seed
   `all all ...`, label `Bitkit Test Trezor`):
   ```sh
   ../bitkit-docker/scripts/trezor-emulator start
   ```
3. For a physical phone, reverse the Bridge port and install with Bridge enabled:
   ```sh
   ../bitkit-docker/scripts/trezor-emulator adb
   TREZOR_BRIDGE=true TREZOR_BRIDGE_URL=http://127.0.0.1:21325 ./gradlew installDevDebug
   ```
   For an Android emulator use `TREZOR_BRIDGE_URL=http://10.0.2.2:21325`.
4. A wallet must exist in the app (onboarding completed) on regtest (dev flavor).

## Journeys

Run in this order — `connect-home-tile.xml` pairs the emulator that the later journeys
rely on, `suggestion-intro-sheet.xml`, `connect-flow.xml` and `settings-hardware-wallets.xml`
each end by re-pairing after a forget, and `detail-overview.xml` runs last because its final
Remove step forgets the device.

| Journey | Covers |
| - | - |
| `connect-home-tile.xml` | Dev-screen connect, home tile, indicator, balance, detail screen opens |
| `activity-blue-icons.xml` | Hardware activity in the unified list, blue icons, All Activity tab filters |
| `activity-detail-hw-tags.xml` | Hardware activity detail tags (persist + survive tag filter) and Explore inputs/outputs |
| `usb-reconnect.xml` | Disconnect indicator, injected USB attach intent → silent auto-reconnect; physical-device chooser path noted separately |
| `suggestion-intro-sheet.xml` | Forget device, Hardware suggestion card, full connect flow (Intro → Searching → Found → Paired → Finish) re-pairs |
| `connect-flow.xml` | Settings Add button → connect flow with an edited Label Funds → paired device count + name |
| `settings-hardware-wallets.xml` | Payments count row, Hardware Wallets screen list, rename sheet, Add button sheet/back dismiss, per-row delete confirm + re-pair |
| `detail-overview.xml` | Detail screen overview, Transfer placeholder when funded, activity, Remove confirm + forget |
| `transfer-to-spending.xml` | Happy-path transfer amount → sign → processing flow with a valid amount below the cap |
| `transfer-to-spending-max-lsp-cap.xml` | MAX when Trezor balance is higher than remaining LSP headroom; verifies MAX uses AVAILABLE and reaches sign without insufficient funds |
| `transfer-to-spending-node-warmup.xml` | Transfer started during app/node warm-up; verifies loading recovers into the sign screen |

Connect-flow testTags: `HardwareWalletSheet`, `HardwareWalletIntroScreen`,
`HardwareWalletIntroCancel`, `HardwareWalletIntroContinue`,
`HardwareWalletSearchingScreen`, `HardwareWalletSearchingCancel`,
`HardwareWalletFoundScreen`, `HardwareWalletFoundCancel`,
`HardwareWalletFoundConnect`, `HardwareWalletPairedScreen`,
`HardwareWalletLabelInput`, `HardwareWalletPairedFinish`,
`HardwareWalletPairCodeScreen` (inline pair code, physical device only),
`HardwareWalletSearchingError`, and `HwFoundError`.

Settings rename testTags: `HardwareWalletsScreen`, `RenameHardwareWalletInput`,
and `RenameHardwareWalletSave`.

The current Connect Hardware sheet starts USB discovery immediately after Continue. BLE is
included only once Android nearby-devices permission is granted and Bluetooth is enabled.
The sheet has no internal back navigation; Android back dismisses the sheet.

If Android shows the Nearby devices/Bluetooth runtime permission prompt after tapping
Continue, allow it and keep waiting on the Searching step. If permission is denied, Bitkit
should show its Bluetooth access recovery dialog with an Open Settings action; that recovery
path is better validated on a physical device because the Bridge path can still find devices
without BLE.

Current journeys pair the standard wallet. Hidden/passphrase wallet behavior is intentionally
not asserted here yet; it needs explicit UX and identity-scoping coverage as described in
synonymdev/bitkit-android#1030.

To exercise the received-money sheet (not covered by a journey because it needs an
out-of-band transfer), fund the emulator wallet on regtest from `bitkit-docker`, e.g.
send to an address generated via Dev Settings → Trezor → Get Address, then mine a block
with `./bitcoin-cli`.

For transfer-to-spending QA, explicitly cover the LSP cap boundary: the hardware wallet
balance can be much larger than the displayed AVAILABLE amount because MAX is capped by
Blocktank channel headroom. After signing, decode the funding transaction and compare the
activity DB row: the on-chain activity fee should be the composed mining fee, while the
funding output should equal the final Blocktank `order.feeSat`.
