---
name: spark-regtest
description: >
  This skill should be used when running a Bitkit dev build on Spark's regtest: building it for
  Spark, funding Savings or the Spark (Spending) balance, paying between dev builds, Move to Spark,
  or Spark emergency exit tests. Triggers: "spark regtest", "fund spark", "spark faucet",
  "ESPLORA_URL", "SPARK_LOCAL_CONFIG_B64", "./spark", "Lightspark regtest", "BREEZ_API_KEY".
version: 0.1.0
---

# Spark regtest

Dev builds run Spark through the Breez SDK. Two Spark regtest networks exist, and neither shares a chain with Blocktank's
staging regtest (`stag0.blocktank.to`). Coins and Lightning payments never cross between them.

| | Lightspark hosted (default) | Local `./spark` (bitkit-docker) |
| --- | --- | --- |
| Use for | Quick checks, physical phones, two-device Spark payments | Lightning to or from an outside node, Move to Spark with a channel, emergency exits, offline E2E |
| Lightning network | None. Builds pay each other only through Spark's provider | Alice, a Lightning node with a channel to the SSP |
| Chain | Lightspark's own | bitkit-docker's local regtest (`spark-bitcoind` peers with `bitcoind`) |
| Funding | Faucet | `./spark pay`, `./spark send` |
| Docs | `scripts/spark-regtest-esplora.py`, `scripts/spark-regtest-fund.py` (headers) | `bitkit-docker/docs/spark-regtest.md` |

Read the named docs and script headers for details. The steps below are what is not obvious from them.

## Lightspark hosted regtest

1. A dev build connects with no key. Without `ESPLORA_URL`, Savings stays on Blocktank's staging chain, so Spark never
   sees a Transfer to Spending deposit and the Transfer Unavailable sheet shows.
2. Put Savings on Spark's chain: run `python3 scripts/spark-regtest-esplora.py --port 3999` (`--bind 0.0.0.0` for a
   phone), then build with `ESPLORA_URL=http://10.0.2.2:3999 ./gradlew assembleDevDebug` (a phone uses the host's LAN
   IP). `ESPLORA_URL` is read from the environment, a Gradle property or `local.properties`.
3. Fund:
   - Web faucet: https://app.lightspark.com/regtest-faucet, at most 50,000 sats per deposit. The user submits it in a
     browser.
   - Script: `SPARK_REGTEST_MNEMONIC=… uv run scripts/spark-regtest-fund.py <bcrt1… address> [sats]`. The mnemonic is a
     GitHub secret; ask the user, never print it.
   - Spark's deposit address is on Settings › Advanced › Lightning Connections › Spark. Fund it to fill Spending
     directly, or fund Savings to test Transfer to Spending.
4. Lightspark mines about once a minute. A deposit is claimed after 1 confirmation.

iOS has no `ESPLORA_URL`, so an iOS build on the hosted regtest can only fund Spark's deposit address directly.

## Local `./spark` stack

Run from a bitkit-docker checkout on `main`. The first `./spark up` builds Rust and Go images and generates operator
keys; expect a long wait.

```bash
./spark up
# Android, from bitkit-android
SPARK_LOCAL_CONFIG_B64="$(../bitkit-docker/spark config --base64)" \
SPARK_LOCAL_CHAIN_URL=http://127.0.0.1:23090/api ./gradlew assembleDevDebug
for port in 23535 23536 23537 23049 23090 60001; do adb reverse tcp:$port tcp:$port; done
```

- Both settings are environment variables only, read at build time. Unset, the build uses the hosted regtest.
- Mine with `./spark mine`, not `./bitcoin-cli mine`: only `spark-bitcoind` relays Spark's v3 packages.
- iOS reads the two settings only in a Debug regtest build that runs as E2E (`E2E_BUILD`, or launch env `E2E=true`)
  with `E2E_BACKEND=local` (`Env.localSparkSettings`). bitkit-e2e-tests' `README.md` (Spark tests on local regtest)
  builds both apps that way with `./scripts/local-backend spark-env`.
- A physical phone needs `SPARK_PUBLIC_HOST=<host LAN IP> ./spark up` or `adb reverse` over USB or wireless adb.

## Mainnet builds

Mainnet needs `BREEZ_API_KEY`, read from the environment, a Gradle property, `local.properties` or the macOS keychain
item `BREEZ_API_KEY` (`README.md`, Build secrets). Regtest needs none. Never print the key.
