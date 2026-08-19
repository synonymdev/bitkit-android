# Trezor Emulator

The deterministic Trezor User Env from [`synonymdev/bitkit-docker`](https://github.com/synonymdev/bitkit-docker) stands in for a physical Trezor during development. It exposes an emulated T2T1 through Trezor Bridge over HTTP, so the app talks to it with the same wallet protocol it uses over USB. Everything except the USB stack, BLE and the one-time pair code is covered; see `journeys/hardware-wallet/README.md` for the full breakdown of what the Bridge transport does and does not simulate.

## Start the Emulator

Run the stack and the helper from a sibling `bitkit-docker` checkout:

```sh
cd ../bitkit-docker
docker compose up -d
./scripts/trezor-emulator start
```

The helper wipes and sets up a deterministic device: model `T2T1`, firmware `2-main`, seed `all all all all all all all all all all all all`, no PIN, label `Bitkit Test Trezor`. Bridge listens on `21325` and the User Env dashboard on `9002`. Linux hosts need the host-network service: `docker compose --profile trezor-linux up -d trezor-user-env-linux`.

Other subcommands: `status` (bridge/emulator state plus a Bridge enumerate), `adb` (reverse the Bridge port for a physical phone), `logs`, `stop`. Helper internals, environment overrides and troubleshooting live in `bitkit-docker/docs/trezor-emulator.md`.

## Build Flags

| Flag | Default | Effect |
| - | - | - |
| `TREZOR_BRIDGE` | `false` | Enables the Bridge transport in `TrezorBridgeTransport`. Without it, Bridge devices are never enumerated. |
| `TREZOR_BRIDGE_URL` | `http://10.0.2.2:21325` | Bridge endpoint. Use `http://127.0.0.1:21325` on a physical phone after `trezor-emulator adb`. |
| `TREZOR_ELECTRUM_URL` | unset | Electrum server for Trezor and hardware wallet flows only. The rest of the app keeps the server configured under Settings → Advanced → Electrum Server. |

Each resolves in the same order: environment variable, then `-P` Gradle property, then `local.properties`. Android Studio builds read `local.properties`; CLI and `just` builds read `.env` or inline environment variables. See `.env.example` for the commented template.

`TREZOR_ELECTRUM_URL` applies only to debug and E2E builds, and only on regtest. It is resolved by `Env.trezorElectrumUrl` and applied in `Env.electrumUrlForNetwork` (the Dev Settings watcher), `TrezorRepo.currentElectrumUrl` (account info, compose, broadcast) and `HwWalletRepo` (the production watchers).

## Install

Android emulator:

```sh
TREZOR_BRIDGE=true TREZOR_BRIDGE_URL=http://10.0.2.2:21325 \
  TREZOR_ELECTRUM_URL=tcp://10.0.2.2:60001 ./gradlew installDevDebug
```

Physical phone, after reversing both the Bridge and Electrum ports:

```sh
../bitkit-docker/scripts/trezor-emulator adb
adb reverse tcp:60001 tcp:60001
TREZOR_BRIDGE=true TREZOR_BRIDGE_URL=http://127.0.0.1:21325 \
  TREZOR_ELECTRUM_URL=tcp://127.0.0.1:60001 ./gradlew installDevDebug
```

## Funding the Emulator Wallet

Copy an address from Dev Settings → Trezor → Get Address, then send and mine from `bitkit-docker`:

```sh
cd ../bitkit-docker
./bitcoin-cli send 0.1 <address>
```

The balance reaches the hardware wallet tile only when `TREZOR_ELECTRUM_URL` points at the local regtest node. Without it, watchers run against the staging regtest Electrum, which does not know about locally mined coins. `Started watcher '<id>' on '<url>'` in the app log confirms which server is in use.

## Smoke Checklist

Open Settings → Advanced → Dev Settings → Trezor, then verify:

- Scan lists the Bridge emulator device
- Connect succeeds and device features are shown
- Get address and get public key succeed
- Sign and verify message succeed
- Send or compose reaches the expected funded or no-funds state
- Disconnect, reconnect and forget-device cleanup behave correctly
