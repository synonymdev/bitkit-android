---
name: spark-regtest
description: >
  This skill should be used when running a Bitkit dev build on Spark's regtest: building it for
  Spark, funding Savings or the Spark (Spending) balance, paying between dev builds, Move to Spark,
  or Spark emergency exit tests. Triggers: "spark regtest", "local spark", "fund spark",
  "spark faucet", "spark deposit address", "Transfer Unavailable", "ESPLORA_URL",
  "SPARK_LOCAL_CONFIG_B64", "./spark", "Lightspark regtest".
version: 0.1.0
---

# Spark regtest

Dev builds run Spark through the Breez SDK. Two Spark regtest networks exist, and neither shares a chain with Blocktank's
staging regtest (`stag0.blocktank.to`). Coins and Lightning payments never cross between them.

| | Lightspark hosted (default) | Local `./spark` (bitkit-docker) |
| --- | --- | --- |
| Use for | Quick checks, physical phones, two-device Spark payments | Lightning to or from a node you control, Move to Spark with a channel, emergency exits, offline E2E |
| Lightning | Only through Lightspark's provider; no node you control | Alice, a Lightning node with a channel to the provider |
| Chain | Lightspark's own | bitkit-docker's local regtest (`spark-bitcoind` peers with `bitcoind`) |
| Funding | Faucet | `./spark pay`, `./spark send` |
| Docs | `scripts/spark-regtest-esplora.py`, `scripts/spark-regtest-fund.py` (headers) | `bitkit-docker/docs/spark-regtest.md` |

Sibling checkouts are assumed next to this repo (`../bitkit-docker`); from a worktree, use the main checkout's
siblings. Run `./spark` commands from bitkit-docker. Read the named docs and script headers for details. The steps below are what is not obvious from them.

## Lightspark hosted regtest

1. A dev build connects with no key. Without `ESPLORA_URL`, Savings stays on Blocktank's staging chain, so the app
   shows the Transfer Unavailable sheet instead of Transfer to Spending (`Env.sparkSharesNodeChain`).
2. Put Savings on Spark's chain: run `python3 scripts/spark-regtest-esplora.py --port 3999`, then build with
   `ESPLORA_URL=http://10.0.2.2:3999 ./gradlew assembleDevDebug`.
   - `ESPLORA_URL` has the same sources as `BREEZ_API_KEY` (environment, Gradle property, `local.properties`, keychain)
     and applies to the dev flavor only.
   - The proxy stops after `--max-seconds` (default 3 h). The build then loses its chain; restart the proxy.
   - A phone: `adb reverse tcp:3999 tcp:3999` with `ESPLORA_URL=http://127.0.0.1:3999`, or `--bind 0.0.0.0` with the
     host's LAN IP.
3. Fund:
   - Web faucet: https://app.lightspark.com/regtest-faucet, up to 50,000 sats per deposit. The user submits it in a
     browser.
   - Script: `SPARK_REGTEST_MNEMONIC=… uv run scripts/spark-regtest-fund.py <bcrt1… address> [sats]`. The mnemonic is a
     GitHub secret. Ask the developer to set it locally, or use the web faucet. Keep it out of chat.
   - Spark's deposit address is on Settings › Advanced › Lightning Connections › Spark. Fund it to fill Spending
     directly, or fund Savings to test Transfer to Spending.
4. Lightspark mines about once a minute. Spark claims a deposit after 3 confirmations unless an Instant transfer claims
   it early.
5. Two-device payment: fund the payer's Spark balance, copy the invoice from Receive › Spending on the other dev build,
   and pay it from the payer. It goes over Lightning through Lightspark's provider.

## Local `./spark` stack

Run `./spark up` from a bitkit-docker checkout on `main`. The app reads the local settings only in a debug, regtest,
local-E2E build (`Env.localSparkSettings`), so the build needs `E2E=true` (`E2E_BACKEND` defaults to `local`). Without
it, the build silently stays on the hosted regtest.

```bash
../bitkit-docker/spark up && \
CFG="$(../bitkit-docker/spark config --base64)" && \
ESPLORA_URL= E2E=true E2E_BACKEND=local SPARK_LOCAL_CONFIG_B64="$CFG" \
SPARK_LOCAL_CHAIN_URL=http://127.0.0.1:23090/api ./gradlew assembleDevDebug && \
for port in 23535 23536 23537 23049 23090 60001; do adb reverse tcp:$port tcp:$port; done
```

- The `&&` chain stops at the first failure. `spark up` can fail while a config from an earlier run survives, and
  `spark config` fails when the stack is not up or the `bitkit-docker` path is wrong; inline, Gradle would still run with
  an empty config, and Spark refuses to start.
- `E2E=true` also points Savings at Electrum on `E2E_LOCAL_HOST:60001` (default `10.0.2.2`), bitkit-docker's chain.
  `ESPLORA_URL=` blanks a hosted proxy URL saved in another source, which Savings would otherwise use instead.
- An existing dev install keeps the Electrum server saved in its settings, which includes the old default. Uninstall
  first (backups are off, so nothing comes back), or use Settings › Advanced › Electrum Server › Reset To Default, then
  Connect To Host (Reset only fills the form).
- On Android the local stack turns off Transfer to Spending and exit fees paid from Savings
  (`Env.sparkSharesNodeChain`). Fund Spark with `./spark send <deposit address> <sats>` or
  `./spark pay <Spending invoice> [sats]`, and pay exit funding with `./spark send`. iOS allows both when its URLs match the fixture.
- A deposit is claimed after 1 block here.
- Mine with `./spark mine`, not `./bitcoin-cli mine`: only `spark-bitcoind` relays Spark's v3 packages.
- A phone over adb: set `E2E_LOCAL_HOST=127.0.0.1` and keep the `adb reverse` loop. Over the LAN: run `export SPARK_PUBLIC_HOST=<LAN IP>`
  before the block, so its `spark up` keeps the host (unset, it rewrites the operator and SSP URLs to `127.0.0.1`), and
  build with `E2E_LOCAL_HOST=<LAN IP>` and `SPARK_LOCAL_CHAIN_URL=http://<LAN IP>:23090/api`.
- bitkit-e2e-tests' `README.md` (Spark tests on local regtest) runs the same stack from its pinned
  `BITKIT_DOCKER_REVISION`, not your checkout.

Regtest needs no `BREEZ_API_KEY`. For mainnet builds see `README.md`, "Breez API key".
