---
name: lsp
description: >
  This skill should be used when the user asks to interact with the Blocktank LSP API,
  "mine blocks", "deposit sats", "pay invoice", "force close a channel", "create a channel order",
  "open a channel", "estimate fees", "create a CJIT channel", or mentions "blocktank", "regtest",
  "LSP", or Lightning channel testing workflows during bitkit development.
version: 0.1.0
---

# Blocktank LSP API

Blocktank is the Lightning Service Provider (LSP) used by Bitkit. This skill provides full knowledge of its REST API and a utility script to call any endpoint from the command line.

No authentication is required. All requests and responses use JSON.

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

# Create a channel order
./lsp POST /channels '{"lspBalanceSat":100000,"channelExpiryWeeks":12}'

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

### Channel Orders

| Method | Path | Description |
|--------|------|-------------|
| POST | `/channels` | Create a channel order |
| GET | `/channels/:id` | Get order by ID |
| GET | `/channels?ids[]=` | Get multiple orders (1-50 IDs) |
| POST | `/channels/:id/open` | Open a paid channel |
| GET | `/channels/:id/min-0conf-tx-fee` | Get 0-conf fee window |
| POST | `/channels/estimate-fee` | Estimate order fee |
| POST | `/channels/estimate-fee-full` | Estimate fee with breakdown |

### CJIT (Just-In-Time Channels)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/cjit` | Create a JIT channel entry |
| GET | `/cjit/:id` | Get CJIT entry status |

### Gift

| Method | Path | Description |
|--------|------|-------------|
| POST | `/gift/pay` | Pay a gift invoice |
| POST | `/gift/order` | Create a gift order |
| GET | `/gift/:id` | Get gift info |

### Regtest Tools (regtest only)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/regtest/chain/mine` | Mine blocks (default: 1) |
| POST | `/regtest/chain/deposit` | Deposit sats to address (default: 100,000) |
| POST | `/regtest/channel/pay` | Pay a Lightning invoice |
| GET | `/regtest/channel/pay/:id` | Get payment status |
| POST | `/regtest/channel/close` | Force close a channel |

## Common Workflows

### Workflow A: Purchase a Channel

1. **Get service info** — `GET /info` to retrieve LSP node pubkeys and channel size limits
2. **Create order** — `POST /channels` with `lspBalanceSat`, `channelExpiryWeeks`, and optional `clientBalanceSat`
3. **Extract payment info** — from response: `payment.onchain.address` (bitcoin address) and `feeSat` (amount to pay)
4. **Fund the order** — `POST /regtest/chain/deposit` with the payment address and fee amount
5. **Confirm payment** — `POST /regtest/chain/mine` with `count: 1` to mine a block
6. **Poll order status** — `GET /channels/:id` until `state2` becomes `paid`
7. **Open channel** — `POST /channels/:id/open` with the client's `connectionStringOrPubkey`
8. **Confirm channel** — `POST /regtest/chain/mine` with `count: 6` to fully confirm

### Workflow B: CJIT Channel (Just-In-Time)

1. **Get service info** — `GET /info` for node pubkeys and limits
2. **Create CJIT entry** — `POST /cjit` with `channelSizeSat`, `invoiceSat`, `nodeId`, `channelExpiryWeeks`
3. **Extract invoice** — from response: `invoice.request` (bolt11 invoice string)
4. **Client pays invoice** — the mobile app pays the invoice, triggering automatic channel opening
5. **Poll status** — `GET /cjit/:id` until `state` becomes `completed`

### Workflow C: Force Close a Channel

1. **Get order info** — `GET /channels/:id` to find `channel.fundingTx.id` and `channel.fundingTx.vout`
2. **Close channel** — `POST /regtest/channel/close` with `fundingTxId`, `vout`, and `forceCloseAfterSec: 0` for immediate close
3. **Mine blocks** — `POST /regtest/chain/mine` with `count: 6` to finalize the closure

### Workflow D: Automated Invoice Payments

Bulk-create and pay invoices to populate the app with payment activity.

**Prerequisites:** Dev debug build installed, wallet set up, LDK node running, open channel with inbound capacity, ADB connected.

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
