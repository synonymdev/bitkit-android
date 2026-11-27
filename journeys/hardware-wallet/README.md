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
Remove step forgets the device. Run the send and receive journeys while the paired native-segwit
account is funded. The `passphrase-*` journeys run as a block after
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
| `transfer-to-spending.xml` | Happy-path transfer into Spark plus one scoped hardware Transfer activity |
| `transfer-to-spending-node-warmup.xml` | Transfer started during app/node warm-up; verifies loading recovers into the sign screen |
| `send-onchain.xml` | Normal Send flow funded by Trezor: source selection, guarded preparation, device signing, broadcast, success, and activity |
| `scan-onchain-amount-over-spendable.xml` | Scanned on-chain amount the Trezor balance covers but not after fees: Insufficient Savings toast instead of a locked Amount screen |
| `jade-connect-sign.xml` | Jade emulator: Hardware suggestion → Found (PIN hint) → Paired, receive-address verification, send signed with Jade |
| `jade-uninitialized.xml` | Jade emulator with no wallet: Found shows the not-set-up message |
| `jade-wrong-pin.xml` | Interactive Jade emulator: wrong PIN on the device → Found shows the wrong-PIN message, retry pairs |
| `jade-network-mismatch.xml` | Jade emulator restricted to mainnet: Found shows the network message |
| `jade-device-locked.xml` | Interactive Jade logged out on the device during an open session: Sign With Jade and Verify on Device ask for the PIN on the device and retry |
| `jade-psbt-too-large.xml` | Jade with about 200 small inputs: sending all of it shows the too-large message |
| `jade-sign-on-device.xml` | Interactive Jade: decline on the device keeps the payment unsent, approve sends it |
| `jade-wallet-mismatch.xml` | Paired Jade reseeded: Verify on Device shows the different-wallet message |
| `jade-passphrase.xml` | Interactive Jade asking for a passphrase: pairing adds the passphrase wallet next to the Jade's own |
| `receive-onchain.xml` | Trezor Receive tab: current address and QR display plus exact on-device address verification |
| `passphrase-pairing.xml` | Passphrase button on Paired → Enter Passphrase → Passphrase Funds Found; second home tile, own label, no passphrase in logs |
| `passphrase-duplicate.xml` | Re-entering a watched passphrase reports "already added" and adds no tile |
| `passphrase-settings-remove.xml` | Per-identity settings row, rename and delete; removing the hidden wallet keeps the device paired |
| `passphrase-transfer-to-spending.xml` | Signs with the live session, re-prompts after the session is dropped, refuses a wrong passphrase |

Broadcast faults on an ordinary send are not a journey capability; the manual recipe is [broadcast-unknown-retry.md](broadcast-unknown-retry.md).

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

Send and receive testTags: `Send`, `RecipientManual`, `RecipientInput`, `AddressContinue`,
`send_amount_screen`, `AssetButton-switch`, `ContinueAmount`, `SendConfirmToggleDetails`, `SendConfirmAssetButton`,
`HardwareSendAmount`, `HardwareSendAddress`, `HardwareSendOpenTrezorConnect`, `SendSuccess`,
`Receive`, `ReceiveScreen`, `QRCode`, `ReceiveHardwareAddress`, and `HardwareVerifyAddress`.

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

For transfer-to-spending QA: a hardware wallet transfer is a deposit to the wallet's Spark
deposit address (`SparkFundingViewModel.onHwBroadcast`), not a Blocktank order. After signing,
decode the funding transaction and compare the activity DB row: the on-chain activity fee
should be the composed mining fee, and the output to the Spark deposit address should equal
the amount entered.

## Blockstream Jade

`jade-connect-sign.xml` drives the Jade flows (Found with the PIN hint, Paired, receive-address
verification, Sign With Jade) against the Jade emulator from `bitkit-docker`: official 1.0.41
firmware under QEMU, seeded with `all all ... all`, locked behind a PIN, serial over TCP on port
30121. The firmware enters its PIN and confirms every prompt itself, so nothing is pressed on the
device.

1. Start it from a sibling `bitkit-docker` checkout:
   ```sh
   ../bitkit-docker/scripts/jade-emulator start
   ```
2. Build the dev debug app with `JADE_EMULATOR=true` in `local.properties`. An Android emulator
   reaches it at the default `JADE_EMULATOR_URL=10.0.2.2:30121`; for a physical phone run
   `../bitkit-docker/scripts/jade-emulator adb` and set `JADE_EMULATOR_URL=127.0.0.1:30121`.
3. The emulator then shows up as a Jade in the Connect Hardware flow, next to USB and Bluetooth
   devices.

The seed is the Trezor emulator's, so the Jade sees the same regtest history and funds. Each
`start` wipes the device back to that seed and PIN; `JADE_MNEMONIC=` (empty) leaves it
uninitialised. USB and Bluetooth transports still need a physical Jade; unit tests (`JadeRepoTest.kt`,
`JadeTransportTest.kt`, `JadeEmulatorTransportTest.kt`, `HwWalletRepoTest.kt`) cover the rest.

The error journeys start the emulator in another state first: `jade-uninitialized.xml`
(`JADE_MNEMONIC=`), `jade-wrong-pin.xml` (`JADE_MODE=interactive`, where the device shows its
own PIN pad and `./scripts/jade-emulator pin` types on it) and `jade-network-mismatch.xml`
(`JADE_NETWORK=mainnet`), and `jade-device-locked.xml` logs the interactive device out during an open session
twice, so Sign With Jade and then Verify on Device ask for the PIN on the device again and retry (the send steps need the app on the bitkit-docker Electrum); `jade-psbt-too-large.xml` needs the app on the bitkit-docker Electrum and about 200 small
inputs on the Jade. `jade-sign-on-device.xml` declines and then approves a payment on the interactive
device's own screens. `jade-wallet-mismatch.xml` restarts the emulator with another seed after pairing. `jade-passphrase.xml` starts it with `JADE_PASSPHRASE=always` and pairs the wallet a passphrase typed on the device opens (`./scripts/jade-emulator passphrase`). The bitkit-docker `docs/jade-emulator.md` lists the modes and recipes.
