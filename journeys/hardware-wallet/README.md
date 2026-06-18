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
  home-screen UI behavior: tiles, balances, activity, indicators, sheets.
- **Partially simulated**: the USB attach → auto-reconnect chain. The OS-level attach
  intent can be injected with `am start -a android.hardware.usb.action.USB_DEVICE_ATTACHED`,
  which drives the full in-app path (MainActivity → AppViewModel → reconnect loop), with
  the Bridge standing in for the transport.
- **Not simulated**: kernel/libusbhost behavior, USB enumeration timing, permission
  grants, the OS app picker, and THP pairing (the Pair Device sheet). Those need a
  physical device.

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
rely on, and `suggestion-intro-sheet.xml` ends by re-pairing after a forget.

| Journey | Covers |
| - | - |
| `connect-home-tile.xml` | Dev-screen connect, home tile, indicator, balance, overview toast |
| `activity-blue-icons.xml` | Hardware activity merge, blue icons, All Activity filters, detail fallback |
| `usb-reconnect.xml` | Disconnect indicator, injected USB attach intent → silent auto-reconnect |
| `suggestion-intro-sheet.xml` | Forget device, Hardware suggestion card, connect intro sheet |

To exercise the received-money sheet (not covered by a journey because it needs an
out-of-band transfer), fund the emulator wallet on regtest from `bitkit-docker`, e.g.
send to an address generated via Dev Settings → Trezor → Get Address, then mine a block
with `./bitcoin-cli`.
