# Blocktank LSP API Reference

Complete reference for all Blocktank LSP HTTP API endpoints.

Base URL: `https://api.stag0.blocktank.to/blocktank/api/v2` (staging)

---

## Service Info

### GET /info

General information about the service, LSP nodes, and channel configuration limits.

**Response:**

```json
{
  "version": 2,
  "versions": {
    "http": "2.5.1",
    "btc": "1.4.0",
    "ln2": "1.25.0"
  },
  "nodes": [
    {
      "alias": "Blocktank",
      "pubkey": "0296b2db342fcf87ea94d981757fdf4d3e545bd5cef4919f58b5d38dfdd73bf5c9",
      "connectionStrings": [
        "0296b2db342fcf87ea94d981757fdf4d3e545bd5cef4919f58b5d38dfdd73bf5c9@172.19.0.2:9735"
      ]
    }
  ],
  "options": {
    "minChannelSizeSat": 1000,
    "maxChannelSizeSat": 3170000,
    "minExpiryWeeks": 1,
    "maxExpiryWeeks": 53,
    "minPaymentConfirmations": 0,
    "minHighRiskPaymentConfirmations": 1,
    "max0ConfClientBalanceSat": 317000
  },
  "onchain": {
    "feeRates": {
      "fast": 54,
      "mid": 50,
      "slow": 49
    }
  }
}
```

---

## Channel Orders

### POST /channels

Create a new channel order.

**Request body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `lspBalanceSat` | integer | Yes | — | LSP-side balance. Min 20,000 sat. |
| `channelExpiryWeeks` | integer | Yes | — | Lease duration. Between minExpiryWeeks and maxExpiryWeeks from /info. |
| `clientBalanceSat` | integer | No | 0 | Client-side balance. Must be <= lspBalanceSat. |
| `zeroConf` | boolean | No | false | Turbo channel (0-conf channel open). |
| `zeroConfPayment` | boolean | No | null | Accept 0-conf onchain payment. Cannot use with clientBalanceSat. |
| `zeroReserve` | boolean | No | false | Zero channel reserve (dust limit). |
| `couponCode` | string | No | null | Discount code. Max 128 chars. |
| `discountCode` | string | No | null | Discount code (newer field). Max 128 chars. |
| `source` | string | No | null | Order source tracking. Max 128 chars. |
| `lspNodeId` | string | No | null | Specific LSP node pubkey. Must be from /info nodes list. |
| `clientNodeId` | string | No | null | Client node pubkey for compliance checks. |
| `signature` | string | No | null | Signature of `channelOpen-${timestamp}` by client node. |
| `timestamp` | string | No | null | ISO datetime used for signature. |
| `announceChannel` | boolean | No | false | Public channel. Cannot be true for zeroConf channels. |
| `refundOnchainAddress` | string | No | null | Refund address. Max 512 chars. |

**Validation rules:**
- `lspBalanceSat + clientBalanceSat` must be between `minChannelSizeSat` and `maxChannelSizeSat`
- `zeroConfPayment` cannot be used when `clientBalanceSat > 0`
- If `signature` is provided, `clientNodeId` and `timestamp` must also be provided

**Response (201):** Order object (see Order Schema below)

**Errors:** 400 (validation error)

### GET /channels/:id

Get a single order by ID.

**Path params:** `id` — UUID or 24-char hex legacy ID

**Response (200):** Order object

**Errors:** 404 (not found)

### GET /channels?ids[]=

Get multiple orders by IDs.

**Query params:** `ids` — Array of UUIDs (1-50, no duplicates). Pass as `?ids[]=abc&ids[]=def`

**Response (200):** Array of Order objects

**Errors:** 404 (not found)

### POST /channels/:id/open

Open a channel for a paid order.

**Path params:** `id` — UUID

**Request body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `connectionStringOrPubkey` | string | Yes | `pubkey@host:port` or just `pubkey`. If pubkey only, client must have an active peer connection to the LSP. |
| `announceChannel` | boolean | No | Announce channel to network. Cannot be true for zeroConf. |

**Response (200):** Updated Order object

**Errors:**
- 400 — Order not in correct state
- 404 — Order not found
- 412 — Channel open failed (see ChannelOpenError)

**Channel open error codes:**
- `WRONG_ORDER_STATE` — Order not in "paid" state
- `PEER_NOT_REACHABLE` — Cannot connect to peer
- `CHANNEL_REJECTED_BY_DESTINATION` — Peer rejected the channel
- `CHANNEL_REJECTED_BY_LSP` — LSP rejected the channel
- `BLOCKTANK_NOT_READY` — Service temporarily unavailable
- `UNKNOWN_ERROR` — Generic error

### GET /channels/:id/min-0conf-tx-fee

Get the minimum onchain fee for a 0-conf payment to be accepted. Valid for at least 2 minutes from time of calling.

**Path params:** `id` — UUID

**Response (200):**

```json
{
  "id": "69ce39f6-4918-416e-9056-8dba678c8af2",
  "satPerVByte": 24.3,
  "validityEndsAt": "2023-07-28T07:39:00.342Z"
}
```

### POST /channels/estimate-fee

Estimate channel order fee without creating an order.

**Request body:** Same as POST /channels

**Response (200):**

```json
{
  "feeSat": 10192,
  "min0ConfTxFee": {
    "satPerVByte": 50.1,
    "validityEndsAt": "2023-07-06T07:58:39.588Z"
  }
}
```

### POST /channels/estimate-fee-full

Estimate fee with network and service fee breakdown.

**Request body:** Same as POST /channels

**Response (200):**

```json
{
  "feeSat": 10192,
  "networkFeeSat": 5096,
  "serviceFeeSat": 5096,
  "min0ConfTxFee": {
    "satPerVByte": 50.1,
    "validityEndsAt": "2023-07-06T07:58:39.588Z"
  }
}
```

---

## CJIT (Just-In-Time Channels)

### POST /cjit

Create a CJIT channel entry. The LSP creates a hold invoice; when paid, the channel opens automatically.

**Request body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `channelSizeSat` | integer | Yes | — | Channel size. Must be >= invoiceSat. Between min/max from /info. |
| `invoiceSat` | integer | Yes | — | Invoice amount. Min 1. |
| `invoiceDescription` | string | No | "" | Invoice description. |
| `channelExpiryWeeks` | integer | Yes | — | Lease duration. Between min/max from /info. |
| `nodeId` | string | Yes | — | Pubkey of the node to open the channel to. |
| `couponCode` | string | No | null | Discount code. Max 128 chars. |
| `source` | string | No | null | Order source tracking. Max 128 chars. |
| `discountCode` | string | No | null | Discount code. Max 128 chars. |
| `zeroReserve` | boolean | No | false | Zero channel reserve. |

**Response (201):** CJitEntry object (see CJitEntry Schema below)

**Errors:** 400 (validation or compliance check failure)

### GET /cjit/:id

Get CJIT entry by ID.

**Path params:** `id` — UUID

**Response (200):** CJitEntry object

**Errors:** 404 (not found)

---

## Gift

### POST /gift/pay

Pay a gift invoice.

**Request body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `invoice` | string | Yes | Valid bolt11 invoice string. |

**Response (200):** Gift object

**Errors:** 400

### POST /gift/order

Create a gift order.

**Request body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `clientNodeId` | string | Yes | Client node ID. |
| `code` | string | Yes | Gift code. |

**Response (200):** Gift object

**Errors:** 400

### GET /gift/:id

Get gift by ID.

**Path params:** `id` — UUID

**Response (200):** Gift object

**Errors:** 400

---

## Regtest Tools

These endpoints are only available when the service is running on regtest network.

### POST /regtest/chain/mine

Mine blocks on the regtest chain.

**Request body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `count` | integer | No | 1 | Number of blocks to mine. Min 1. |

**Response (200):** Mining result

### POST /regtest/chain/deposit

Send satoshis to a regtest bitcoin address (faucet).

**Request body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `address` | string | Yes | — | Regtest bitcoin address (must be a valid regtest address). |
| `amountSat` | integer | No | 100000 | Amount in satoshis. Min 1. |

**Response (200):** Transaction ID string

### POST /regtest/channel/pay

Pay a Lightning invoice on regtest.

**Request body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `invoice` | string | Yes | — | Valid regtest bolt11 invoice. |
| `amountSat` | integer | No | null | Amount for 0-amount invoices. Min 1. |

**Response (200):** Payment ID string (UUID)

### GET /regtest/channel/pay/:id

Get payment status by ID.

**Path params:** `id` — UUID (payment ID from POST /regtest/channel/pay)

**Response (200):** Payment object with invoice state

### POST /regtest/channel/close

Force close a channel.

**Request body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `fundingTxId` | string | Yes | — | Funding transaction ID from `channel.fundingTx.id`. |
| `vout` | integer | Yes | — | Output index from `channel.fundingTx.vout`. Min 0. |
| `forceCloseAfterSec` | integer | No | 86400 | Seconds before force close. Use 0 for immediate force close. |

**Response (200):** Closing transaction ID string

---

## Response Schemas

### Order

```json
{
  "id": "fa7e6d29-a04d-47ea-8db4-ec05f6b8601c",
  "state": "open",
  "state2": "executed",
  "orderExiresAt": "2023-07-06T07:58:39.588Z",
  "feeSat": 19021,
  "lspBalanceSat": 3000000,
  "clientBalanceSat": 0,
  "channelExpiryWeeks": 12,
  "channelExpiresAt": "2023-10-06T07:58:39.588Z",
  "couponCode": "",
  "zeroConf": false,
  "zeroReserve": false,
  "discountPercent": 0,
  "lspNode": {
    "alias": "Blocktank",
    "pubkey": "0296b2db...",
    "connectionStrings": ["0296b2db...@172.19.0.2:9735"]
  },
  "channel": {
    "state": "open",
    "lspNodePubkey": "0296b2db...",
    "clientNodePubkey": "0386b2db...",
    "announceChannel": false,
    "shortChannelId": "792906x599x1",
    "fundingTx": {
      "id": "fa205519e0eb80f84a6c234b1c7f5a2cc6995eb4d84b6345fab214097d79b38d",
      "vout": 1
    },
    "closingTxId": null,
    "close": null
  },
  "payment": {
    "state": "paid",
    "state2": "paid",
    "paidSat": 19021,
    "bolt11Invoice": {
      "request": "lntb1u1pwz5w78pp5...",
      "state": "paid",
      "amountSat": 19021,
      "expiresAt": "2023-07-06T07:58:39.588Z",
      "updatedAt": "2023-07-06T07:58:39.588Z"
    },
    "onchain": {
      "requiredConfirmations": 1,
      "address": "bcrt1q66jwcerttp8jcu43mlvz0y93v8pf6lxh5xztq3",
      "confirmedSat": 19021,
      "transactions": [
        {
          "amountSat": 19021,
          "txId": "fa205519...",
          "vout": 0,
          "blockHeight": 600100,
          "blockConfirmations": 3,
          "feeRateSatPerVbyte": 21.1,
          "confirmed": true
        }
      ]
    }
  },
  "updatedAt": "2023-07-06T07:58:39.588Z",
  "createdAt": "2023-07-06T07:58:39.588Z"
}
```

**Order states (`state2`):** `created`, `paid`, `executed`, `expired`

**Payment states (`payment.state2`):** `created`, `paid`, `refunded`, `refundAvailable`, `canceled`

**Channel states (`channel.state`):** `opening`, `open`, `closed`

**Channel close types:** `cooporative`, `force`, `breach`

**Channel close initiator:** `lsp`, `client`

### CJitEntry

```json
{
  "id": "fa7e6d29-a04d-47ea-8db4-ec05f6b8601c",
  "state": "created",
  "feeSat": 19021,
  "channelSizeSat": 3000000,
  "channelExpiryWeeks": 12,
  "channelOpenError": null,
  "nodeId": "03775370500b8c8642617bced873e7914eaec4f6a79c9ca99043224a1b28677082",
  "invoice": {
    "request": "lntb1u1pwz5w78pp5...",
    "state": "pending",
    "amountSat": 2000000,
    "expiresAt": "2023-07-06T07:58:39.588Z",
    "updatedAt": "2023-07-06T07:58:39.588Z"
  },
  "channel": {
    "state": "opening",
    "lspNodePubkey": "0296b2db...",
    "clientNodePubkey": "03775370...",
    "announceChannel": false,
    "fundingTx": null,
    "close": null
  },
  "lspNode": {
    "alias": "Blocktank",
    "pubkey": "0296b2db...",
    "connectionStrings": ["0296b2db...@172.19.0.2:9735"]
  },
  "couponCode": "",
  "expiresAt": "2023-07-06T07:58:39.588Z",
  "updatedAt": "2023-07-06T07:58:39.588Z",
  "createdAt": "2023-07-06T07:58:39.588Z"
}
```

**CJIT states:** `created`, `completed`, `expired`, `failed`

### ChannelOpenError

Returned as HTTP 412 from POST /channels/:id/open:

```json
{
  "message": "Channel has been rejected by the client.",
  "code": "CHANNEL_REJECTED_BY_DESTINATION",
  "details": {},
  "name": "ChannelOpenError"
}
```

**Error codes:** `WRONG_ORDER_STATE`, `PEER_NOT_REACHABLE`, `CHANNEL_REJECTED_BY_DESTINATION`, `CHANNEL_REJECTED_BY_LSP`, `BLOCKTANK_NOT_READY`, `UNKNOWN_ERROR`
