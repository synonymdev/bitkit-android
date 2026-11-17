# Blocktank LSP API Reference

Reference for the Blocktank LSP HTTP API routes staging still serves.

Blocktank no longer opens channels: creating an order, opening a paid order, creating a CJIT entry and the gift routes return 404. The order fee estimates still answer but no order can use them, so they are left out. Orders and CJIT entries can still be read, for wallets that hold them from before the sunset. `POST /regtest/channel/close` is gone (500 `Worker method closeChannelByFundingTx not found`).

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

## Channel Orders (lookup only)

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

## CJIT Entries (lookup only)

### GET /cjit/:id

Get CJIT entry by ID.

**Path params:** `id` — UUID

**Response (200):** CJitEntry object

**Errors:** 404 (not found)

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
