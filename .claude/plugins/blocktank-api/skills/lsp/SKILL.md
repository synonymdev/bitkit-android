---
name: lsp
description: >
  This skill should be used when the user asks to interact with the Blocktank LSP API on
  staging regtest, "mine blocks", "deposit sats", "pay invoice", "look up a Blocktank order" or
  "CJIT entry", or mentions "blocktank" or "LSP" during bitkit development.
version: 0.1.0
---

# Blocktank LSP API

Blocktank was Bitkit's Lightning Service Provider (LSP). It no longer opens channels: on staging, creating an order, opening a paid order, creating a CJIT entry and the gift routes return 404, and `POST /regtest/channel/close` fails (500 `Worker method closeChannelByFundingTx not found`). What staging still serves, and this skill covers, is service info, lookups of existing orders and CJIT entries, and the regtest chain and payment tools. Spending balances now come from Spark, which this skill does not cover.

All requests and responses use JSON. Staging's `/regtest/*` routes need basic auth: set `BLOCKTANK_REGTEST_AUTH=user:password` and the script sends it; without it they return `401`. Other routes need no authentication. Debug builds of the app send the same value from Settings > Blocktank Regtest when `BLOCKTANK_REGTEST_AUTH` is set at build time (environment variable, Gradle property or `local.properties`); release builds never carry it.

## Configuration

**Default base URL:** `https://api.stag0.blocktank.to/blocktank/api/v2` (staging)

Override with the `BLOCKTANK_API_URL` environment variable:
- Local instance: `http://localhost:9000/api`

## API Script

Call any endpoint using the `./lsp` wrapper at the repo root:

```bash
./lsp <GET|POST> <path> [json_body]
```

Examples:

```bash
# Get service info
./lsp GET /info

# Mine 6 blocks
./lsp POST /regtest/chain/mine '{"count":6}'

# Deposit to an address
./lsp POST /regtest/chain/deposit '{"address":"bcrt1q...","amountSat":500000}'
```

The script outputs raw JSON. Pipe to `jq` for formatting if needed.

On HTTP errors (4xx/5xx), the script prints the status code to stderr and the error response body to stdout, then exits with code 1.

## Endpoint Quick Reference

### Service Info

| Method | Path | Description |
|--------|------|-------------|
| GET | `/info` | Service info, LSP nodes, channel size limits, fee rates |

### Channel Orders (lookup only)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/channels/:id` | Get order by ID |
| GET | `/channels?ids[]=` | Get multiple orders (1-50 IDs) |

### CJIT Entries (lookup only)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/cjit/:id` | Get CJIT entry status |

### Regtest Tools (regtest only)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/regtest/chain/mine` | Mine blocks (default: 1) |
| POST | `/regtest/chain/deposit` | Deposit sats to address (default: 100,000) |
| POST | `/regtest/channel/pay` | Pay a Lightning invoice |
| GET | `/regtest/channel/pay/:id` | Get payment status |

## Common Workflows

### Automated Invoice Payments

Bulk-create and pay invoices to populate the app with payment activity.

**Prerequisites:** Dev debug build installed, wallet set up, LDK node running, an existing channel with the Blocktank staging node that has inbound capacity (Blocktank opens no new ones), ADB connected.

**Run with defaults** (21 invoices of 1..21 sats, mine 150 blocks in batches of 10):

```bash
"${CLAUDE_PLUGIN_ROOT}/skills/lsp/scripts/pay-invoices.sh"
```

**Custom parameters** via env vars:

```bash
INVOICE_COUNT=10 DESCRIPTION="test-ovi-{i}" MINE_TOTAL=60 MINE_BATCH=10 \
  "${CLAUDE_PLUGIN_ROOT}/skills/lsp/scripts/pay-invoices.sh"
```

- `DESCRIPTION` — invoice description; `{i}` is replaced with the invoice index (default: `dev-payment-{i}`)

The script uses the `DevToolsProvider` ContentProvider (dev builds only) to create invoices on the app's LDK node via `adb shell content call`, then pays each via the LSP's `POST /regtest/channel/pay` endpoint.

**Create a single invoice manually:**

```bash
adb shell "content call --uri content://to.bitkit.dev.devtools \
  --method createInvoice --arg '{\"amount\":1000,\"description\":\"test\"}'"
```

## State Machines

These describe the orders and CJIT entries the lookup routes return.

### Order States (`state2`)

```
created → paid → executed
    ↘ expired
```

- `created` — waiting for payment
- `paid` — payment confirmed, ready to open channel
- `executed` — channel opened successfully
- `expired` — order timed out

### Payment States (`payment.state2`)

```
created → paid → refundAvailable → refunded
    ↘ canceled
```

### Channel States (`channel.state`)

```
opening → open → closed
```

### CJIT States (`state`)

```
created → completed
    ↘ expired
    ↘ failed
```

## Detailed API Reference

For full request/response schemas, field constraints, and error codes for every endpoint, consult:

- **`references/api-reference.md`** — Complete API reference with all fields, types, defaults, and validation rules
