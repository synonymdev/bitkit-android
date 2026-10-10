# Hardware wallet (Trezor and Jade): pairing, tile, settings, receive, send

Watch-only hardware wallets: pairing, balance tile, detail screen, settings, passphrase (hidden) wallets, receive address verification and on-chain send signed on the device. Transfer to spending from a device is in `hardware-wallet-transfer.md`.

## What it does
- Vendors: `HwWalletVendor.TREZOR` (USB, Bluetooth, and the dev-only Bridge transport) and `BLOCKSTREAM` (Jade, USB serial and Bluetooth). Paired devices are kept as watch-only xpub watchers, one per (wallet, address type); balances and activity come from `HwWalletRepo` and feed home, All Activity and the headline total.
- Connect flow (`HardwareSheet`): Intro > Searching > Found > Paired (label field "Label Funds"), plus a Pair Device step when the device asks for its one-time pairing code (`HwPairPinSheet.kt`, composable `HwPairCodeSheet`). Continue starts USB discovery at once; Bluetooth is included once the nearby-devices permission is granted and Bluetooth is on.
- Passphrase wallets (Trezor only): from Paired, `Passphrase` opens Enter Passphrase; the hidden wallet becomes its own identity with its own tile, label, balance, settings row and activity scope. The passphrase is never stored; a duplicate shows "already added". A Jade has no passphrase wallets.
- A wallet id is derived from the account xpubs (`HwWalletId`), so activities are scoped by `walletId`; the device holds one identity open at a time, so signing first proves the open session belongs to the wallet (`needsPassphrase`, mismatch errors).
- Detail screen: balance, activity of that wallet, `Transfer To Spending` when the native-segwit funding balance is above 0, Remove (dialog with a "keep backup data" toggle).
- Receive: the Receive sheet shows a hardware tab when exactly one hardware wallet exists (or the sheet was opened from that wallet's screen) and can verify the address on the device.
- Send: the normal Send sheet can pick the hardware wallet as funding source; swipe leads to `HwSendSignScreen`, which composes, signs and broadcasts (timeouts compose 45 s, sign 120 s, broadcast 120 s in `HwSendViewModel`).
- USB attach (`UsbManager.ACTION_USB_DEVICE_ATTACHED`, `MainActivity.handleUsbAttachIntent`): reconnects a known device silently; an unknown Trezor/Jade opens the Found step.

## How a user reaches it
- Home suggestion `Suggestion-hardware` opens `Sheet.Hardware`; Settings > General (Payments section) row `HardwareWalletsSettings` > `HardwareWalletsScreen` > `AddHardwareWallet` opens the same sheet.
- Sheet ids: `HardwareWalletSheet`, `HardwareWalletIntroScreen` (`HardwareWalletIntroCancel`, `HardwareWalletIntroContinue`), `HardwareWalletSearchingScreen` (`HardwareWalletSearchingCancel`, error `HardwareWalletSearchingError`), `HardwareWalletFoundScreen` (`HardwareWalletFoundConnect`, `HardwareWalletFoundCancel`, `HwFoundError`, `HwFoundUnlockHint`), `HardwareWalletPairedScreen` (`HardwareWalletLabelInput`, `HardwareWalletPairedPassphrase`, `HardwareWalletPairedFinish`), `HardwareWalletPairCodeScreen`, `HardwareWalletPassphraseScreen` (`HardwareWalletPassphraseInput`, `HardwareWalletPassphraseBack`, `HardwareWalletPassphraseContinue`), `HardwareWalletPassphrasePairedScreen`.
- Home tile `ActivityHardware` (one per wallet identity, same tag) > `HardwareWalletScreen` (`TotalBalance`, `HardwareTransferToSpending`, `RemoveHardwareWallet`, dialog `RemoveHwWalletDialog`, `HwRemoveKeepBackupToggle`, confirm `DialogConfirm`).
- Settings rows: `HardwareWalletRow_<id>`, `HardwareWalletRowName<id>` (opens rename sheet `RenameHardwareWalletSheet<id>`, `RenameHardwareWalletInput`, `RenameHardwareWalletSave`), `HardwareWalletRowDelete_<id>`. The `<id>` is the wallet id (`trezor:<hex>` style).
- Receive: `Receive` > `Tab-hardware` (label is the vendor name) > `ShowDetails`, `ReceiveHardwareAddress`, `HardwareVerifyAddress`, `QRCode`.
- Send: `Send` > `RecipientManual`, `RecipientInput`, `AddressContinue` > `send_amount_screen`, funding button `AssetButton-switch` (tag is `AssetButton-<switch|trezor|savings|spending>`), `ContinueAmount` > `SendConfirm`, `SendConfirmAssetButton`, `GRAB` > sign screen (`HwSendSignScreen`, no screen tag) with `HardwareSendAmount`, `HardwareSendAddress`, `HardwareSendOpenTrezorConnect` > `SendSuccess`, `Close` (see `send.md`).
- Dev Trezor screen: Settings > Advanced > `DevSettings` (row shown when dev mode is on, `isDevModeEnabled`) > row "Trezor" (`Routes.Trezor`, `TrezorScreen`): scan, connect, forget, address, sign message, xpub, balance lookup, history, send; no testTags.

## Code
- Sheets in `app/src/main/java/to/bitkit/ui/sheets/hardware/`: `HardwareSheet`, `HwIntroSheet`, `HwSearchingSheet`, `HwFoundSheet`, `HwPairedSheet`, `HwPairPinSheet` (`HwPairCodeSheet`), `HwPassphraseSheet`, `HwPassphrasePairedSheet`, `HwConnectViewModel`.
- Screens: `ui/screens/wallets/HardwareWalletScreen.kt`, `HwWalletViewModel.kt`, `RemoveHwWalletDialog.kt`, `ui/settings/general/HardwareWalletsSettingsScreen.kt`, `ui/screens/wallets/send/HwSendSignScreen.kt`, `HwSendViewModel.kt`, `ui/screens/wallets/receive/HwReceiveViewModel.kt`, `ui/screens/trezor/` (`TrezorScreen`, `TrezorViewModel`, sections and dialogs), `ui/components/HwWalletComponents.kt`.
- Repos: `repositories/HwWalletRepo.kt` (business layer: wallets, watchers, balances, funding, routing by vendor), `TrezorRepo.kt` (Trezor sessions plus vendor-neutral watchers, compose, broadcast), `JadeRepo.kt` (Jade sessions, address verification, PSBT signing); services `TrezorService`, `TrezorTransport`, `TrezorBridgeTransport`, `JadeService`, `JadeTransport`; models `models/HardwareWallet.kt`, `HwWalletId.kt`, `HwDevicePath.kt`; store `data/HwWalletStore.kt`; errors `utils/HwErrorPresenter.kt`, `TrezorErrorPresenter.kt`, `ui/utils/HwUsbId.kt`.
- Routes (`ui/ContentView.kt`): `Routes.HardwareWallet(walletId)`, `HardwareWalletsSettings`, `Trezor`; `Sheet.Hardware(route)` with `HardwareRoute` (`Found`, `PairCode`, ...); `SendRoute.HardwareSign`; `Sheet.Send(hardwareWalletId)`, `Sheet.Receive(hardwareWalletId)`.
- Bridge: build config `TREZOR_BRIDGE` and `TREZOR_BRIDGE_URL` (`build.gradle.kts` local properties; default URL `http://10.0.2.2:21325`) read by `TrezorBridgeTransport`.

## How to drive it
- Setup (`journeys/hardware-wallet/README.md`): `docker compose up -d` in `bitkit-docker`, `../bitkit-docker/scripts/trezor-emulator start` (passphrase journeys: `TREZOR_PASSPHRASE_PROTECTION=true`), `trezor-emulator adb` for a phone, build with `TREZOR_BRIDGE=true TREZOR_BRIDGE_URL=...`; device prompts are approved with `trezor-emulator send-json '{"type":"emulator-press-yes"}'`.
- Journeys (order matters, see README): `connect-home-tile.xml` (pair via dev screen, tile, detail), `suggestion-intro-sheet.xml`, `connect-flow.xml`, `settings-hardware-wallets.xml` (each forgets and re-pairs), `usb-reconnect.xml` (injects the USB attach intent), `passphrase-pairing.xml`, `passphrase-duplicate.xml`, `passphrase-settings-remove.xml`, `send-onchain.xml`, `receive-onchain.xml`, `activity-blue-icons.xml`, `activity-detail-hw-tags.xml`, `detail-overview.xml` (last; it forgets the device). Transfer journeys: `hardware-wallet-transfer.md`.
- E2E `bitkit-e2e-tests/test/specs/hardware-wallet.e2e.ts`, describe `@hardware_wallet`: `@hardware_wallet_1` connect, rename, show, remove; `_2` receive on-chain to the device; `_3` transfer to spending; `_4` send on-chain from the device. Helpers `bitkit-e2e-tests/test/helpers/hardware-wallet.ts` start the emulator via `bitkit-e2e-tests/scripts/trezor-emulator`. CI: staging shard `hardware_wallet` in `e2e-staging.yml` (`BACKEND=regtest`, pulls the `trezor-user-env` image); no local shard greps it.
- Unit: `HwWalletRepoTest`, `TrezorRepoTest`, `JadeRepoTest`, `HwConnectViewModelTest`, `HwWalletViewModelTest`, `HwSendViewModelTest`, `HwReceiveViewModelTest`, `TrezorViewModelTest`, `TrezorBridgeTransportTest`, `JadeTransportTest`, `JadeServiceTest`, `HwErrorPresenterTest`, `HwUsbIdTest`; androidTest `ReceiveHardwareFlowTest.kt`.

## What proves it
- Pairing: `ActivityHardware` tile with the label and a green connection icon; Hardware suggestion gone; row in settings; after Remove the tile and row are gone.
- Receive: `ActivityHardware` balance equals the deposit; `ActivityHardware` > `Activity-1` shows "Received" and the amount; total balance sums savings and hardware.
- Send: `SendSuccess`, the wallet's `Activity-1` shows "-", detail shows the tag chip `Tag-<tag>-delete`, hardware balance below the funding amount.
- Passphrase: second tile with its label, headline total includes both; the journey greps the app log and datastore for the passphrase string.

## Not covered by tests
- Bluetooth pairing, Bluetooth permission dialog, pair-code step (THP) and OS app picker: need a physical device (journeys README).
- Jade end to end (USB, BLE, PIN on device, signing): unit tests and manual runs only; no Jade emulator in `bitkit-docker`.
- Received-money sheet for a hardware deposit: README says no journey (needs an out-of-band transfer).
- Rename from the settings row and delete of a passphrase identity: in journeys only; e2e `_1` renames but not passphrase wallets.
- Dev Trezor screen sections (sign message, public key, history, watcher): no journey or e2e.
- Two or more hardware wallets in Receive (hardware tab hidden unless opened from the wallet screen): not found in tests.

## Gotchas
- Bridge covers the wallet protocol and UI, not USB enumeration, permission grants, OS picker, BLE or the inline pair code.
- The Bridge device pairs without the pair-code step; a Jade/BLE connect can wait minutes for the PIN on the device (`JADE_RECONNECT_TIMEOUT` 5 min vs `TREZOR_RECONNECT_TIMEOUT` 30 s in `HwWalletRepo`).
- Several journeys end by forgetting and re-pairing the device; run them in the README order, `detail-overview.xml` last.
- `BlockScreenshots` is a no-op in debug builds, so passphrase steps stay screenshottable.
- `journeys/README.md` identifier table: the receive tab is `Tab-hardware` here and `Tab-trezor`/`Tab-jade` on iOS; the e2e helper accepts both.
