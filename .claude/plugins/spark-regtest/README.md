# Spark Regtest Plugin

A Claude Code plugin with the `spark-regtest` skill: build a Bitkit dev build for Spark's regtest, fund it and pick
between Lightspark's hosted regtest and bitkit-docker's local `./spark` stack.

## Setup

Run once from the repo root (re-run anytime to update):

```sh
./.claude/install-plugins.sh
```

Restart Claude Code once. The `spark-regtest:spark-regtest` skill is then available.

## Usage

The skill triggers on things like "spark regtest", "fund spark", "spark faucet", "ESPLORA_URL" or "./spark".
