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
  grants, the OS app picker, BLE runtime/settings recovery, and the THP one-time pairing code
  (the inline Pair Device step). Those need a physical device.
- **Simulated with extra setup**: passphrase (hidden) wallets. The emulator derives a separate
  account set per passphrase, but the device must be set up with passphrase protection enabled —
  see the prerequisites below. Host-side entry is the only mode Bitkit ships, so nothing has to
  be typed on the emulated device.

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
   The `passphrase-*` journeys additionally need passphrase protection enabled on the device:
   ```sh
   TREZOR_PASSPHRASE_PROTECTION=true ../bitkit-docker/scripts/trezor-emulator start
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
Remove step forgets the device. Run the send journey while the paired native-segwit account is
funded. The `passphrase-*` journeys run as a block after
`connect-home-tile.xml`, in the order listed: `passphrase-pairing.xml` pairs the hidden wallet
the other three rely on, and `passphrase-settings-remove.xml` removes it again.

| Journey | Covers |
| - | - |
| `connect-home-tile.xml` | Dev-screen connect, home tile, indicator, balance, detail screen opens |
| `activity-blue-icons.xml` | Wallet-scoped hardware activity, blue icons, unified list, and tab filters |
| `activity-detail-hw-tags.xml` | Persisted hardware tags and Explore inputs/outputs |
| `usb-reconnect.xml` | Disconnect indicator, injected USB attach intent → silent auto-reconnect; physical-device chooser path noted separately |
| `suggestion-intro-sheet.xml` | Forget device, Hardware suggestion card, full connect flow (Intro → Searching → Found → Paired → Finish) re-pairs |
| `connect-flow.xml` | Settings Add button → connect flow with an edited Label Funds → paired device count + name |
| `settings-hardware-wallets.xml` | Payments count row, Hardware Wallets screen list, rename sheet, Add button sheet/back dismiss, per-row delete confirm + re-pair |
| `detail-overview.xml` | Detail screen overview, Transfer placeholder when funded, activity, Remove confirm + forget |
| `transfer-to-spending.xml` | Happy-path transfer plus one scoped hardware Transfer activity |
| `transfer-to-spending-max-lsp-cap.xml` | MAX when Trezor balance is higher than remaining LSP headroom; verifies MAX uses AVAILABLE and reaches sign without insufficient funds |
| `transfer-to-spending-node-warmup.xml` | Transfer started during app/node warm-up; verifies loading recovers into the sign screen |
| `send-onchain.xml` | Normal Send flow funded by Trezor: source selection, guarded preparation, device signing, broadcast, success, and activity |
| `passphrase-pairing.xml` | Passphrase button on Paired → Enter Passphrase → Passphrase Funds Found; second home tile, own label, no passphrase in logs |
| `passphrase-duplicate.xml` | Re-entering a watched passphrase reports "already added" and adds no tile |
| `passphrase-settings-remove.xml` | Per-identity settings row, rename and delete; removing the hidden wallet keeps the device paired |
| `passphrase-transfer-to-spending.xml` | Signs with the live session, re-prompts after the session is dropped, refuses a wrong passphrase |

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

Passphrase testTags: `HardwareWalletPairedPassphrase`, `HardwareWalletPassphraseScreen`,
`HardwareWalletPassphraseInput`, `HardwareWalletPassphraseBack`,
`HardwareWalletPassphraseContinue`, `HardwareWalletPassphrasePairedScreen`, and on the transfer
sign screen `HwTransferPassphraseSheet`, `HwTransferPassphraseInput`,
`HwTransferPassphraseCancel`, `HwTransferPassphraseContinue`.

Send testTags: `Send`, `RecipientManual`, `RecipientInput`, `AddressContinue`,
`send_amount_screen`, `AssetButton-switch`, `ContinueAmount`, `SendConfirmAssetButton`,
`HardwareSendAmount`, `HardwareSendAddress`, `HardwareSendOpenTrezorConnect`, and `SendSuccess`.

The current Connect Hardware sheet starts USB discovery immediately after Continue. BLE is
included only once Android nearby-devices permission is granted and Bluetooth is enabled.
The sheet has no internal back navigation; Android back dismisses the sheet.

If Android shows the Nearby devices/Bluetooth runtime permission prompt after tapping
Continue, allow it and keep waiting on the Searching step. If permission is denied, Bitkit
should show its Bluetooth access recovery dialog with an Open Settings action; that recovery
path is better validated on a physical device because the Bridge path can still find devices
without BLE.

A physical device holds one hidden wallet open at a time, and Bitkit never stores the
passphrase, so the `passphrase-*` journeys assert both halves of that: the tile, label,
settings row, activity scope and removal are per identity, while signing reuses the live
session and asks again once it is gone. They also grep the app log and datastore to prove the
passphrase is never written anywhere — note `BlockScreenshots` is a no-op in debug builds, so
the passphrase steps remain screenshottable while journeys run.

To exercise the received-money sheet (not covered by a journey because it needs an
out-of-band transfer), fund the emulator wallet on regtest from `bitkit-docker`, e.g.
send to an address generated via Dev Settings → Trezor → Get Address, then mine a block
with `./bitcoin-cli`.

The activity journeys use a mixed fixture with two confirmed hardware receives carrying
distinct amounts and one hardware Transfer To Spending transaction. This proves
ordering, tab filtering, tags, inputs/outputs, transfer metadata, and row uniqueness together.

For transfer-to-spending QA, explicitly cover the LSP cap boundary: the hardware wallet
balance can be much larger than the displayed AVAILABLE amount because MAX is capped by
Blocktank channel headroom. After signing, decode the funding transaction and compare the
activity DB row: the on-chain activity fee should be the composed mining fee, while the
funding output should equal the final Blocktank `order.feeSat`.
