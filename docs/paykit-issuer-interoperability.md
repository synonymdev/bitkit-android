# Paykit issuer interoperability

This is the Bitkit issuer contract for one-time Paykit Payment Requests. It describes the request and payment-endpoint shapes an issuer must provide for Bitkit to present and open a request. The canonical accepted and rejected examples are in
[`app/src/test/resources/paykit-issuer-interoperability.json`](../app/src/test/resources/paykit-issuer-interoperability.json).

This contract records Bitkit behavior. Paykit protocol or SDK policy remains owned by Paykit.

## Payment Request

An actionable request must satisfy all of these requirements:

- Without conversion terms, the amount asset is exactly lowercase `btc`, with a positive decimal Bitcoin value and at most eight significant fractional digits.
- With fixed conversion terms, the amount may use another denomination; Bitkit multiplies it by the issuer's BTC rate. The payable amount must not exceed `18,446,744,073,709,551` satoshis.
- The request is one-time: the local role is payer, its actionable lifecycle state is proposed or accepted, and recurrence is absent.
- The proposal expiration is absent or is a valid ISO 8601 timestamp. It must be in the future while the request is proposed; it does not prevent payment after acceptance.
- `paymentDeadline` is absent or uses the absolute `At` form with a valid UTC ISO 8601 timestamp ending in `Z` that has not passed. The deadline is inclusive: payment is allowed at the exact deadline instant.
- `acceptedPaymentEndpointIdentifiers` retains at least one identifier supported on the wallet's current network.

Bitkit filters `acceptedPaymentEndpointIdentifiers` in issuer order, removes duplicates after their first occurrence, and drops unknown or wrong-network identifiers. The request remains actionable when at least one identifier survives.

Bitkit enforces absolute one-time payment deadlines during payment preparation and before submission, including retries. Requests whose valid absolute deadline has passed remain visible in history but are unavailable for payment. This is separate from proposal expiration, which controls acceptance.

Malformed deadlines and relative deadline forms are not actionable. Recurring requests are outside this one-time contract.

### Fixed pricing

An exact asset-and-rail rate (such as `btc-bitcoin` or `btc-regtest`) takes precedence
over a `btc` rate, including when the requested asset is already BTC. Only an unpriced
same-asset endpoint defaults to parity. Cross-asset endpoints without a matching rate
are excluded. Amounts and rates use positive decimal strings without signs or exponents;
Bitkit accepts at most 80 characters and 38 significant digits per operand and rejects
unrepresentable arithmetic instead of approximating it.

For cross-platform support, the exact amount-times-rate product must also fit within
38 significant digits after trailing zeros are removed. Android rejects larger products;
iOS can accept some larger products when Foundation Decimal represents them exactly.
Issuers must not rely on that extra iOS range for Android recipients.

On-chain amounts round upward once to satoshis. Lightning amounts round upward once
to millisatoshis and must then be exactly representable in whole satoshis. The existing
send sheet uses one amount across its available rails: requests with differing BTC
amounts across those rails, per-period quotes, and conversion-based subscriptions are
unsupported. Normal unpriced BTC requests are unchanged.

Bitkit displays the payable BTC amount and its own market-rate fiat estimate, not the
original denomination as a fiat estimate. Original request terms remain unchanged in Paykit.
The quoted amount is also used for invoice matching and payment validation.

### Endpoint identifiers

Lightning identifiers are chain-independent and are accepted on every network:

- `btc-lightning-bolt11`
- `btc-lightning-lnurl`

On-chain identifiers include the wallet network:

| Network | P2TR | P2WPKH | P2SH | P2PKH |
| --- | --- | --- | --- | --- |
| Bitcoin | `btc-bitcoin-p2tr` | `btc-bitcoin-p2wpkh` | `btc-bitcoin-p2sh` | `btc-bitcoin-p2pkh` |
| Testnet | `btc-testnet-p2tr` | `btc-testnet-p2wpkh` | `btc-testnet-p2sh` | `btc-testnet-p2pkh` |
| Signet | `btc-signet-p2tr` | `btc-signet-p2wpkh` | `btc-signet-p2sh` | `btc-signet-p2pkh` |
| Regtest | `btc-regtest-p2tr` | `btc-regtest-p2wpkh` | `btc-regtest-p2sh` | `btc-regtest-p2pkh` |

For example, a regtest issuer can propose:

```json
{
  "amount": { "value": "0.001", "asset": "btc" },
  "paymentReference": { "text": "marketplace-order-713" },
  "proposalExpiresAt": "2030-01-01T00:00:00Z",
  "recurrence": null,
  "acceptedPaymentEndpointIdentifiers": [
    "btc-regtest-p2wpkh",
    "btc-lightning-bolt11"
  ],
  "metadata": { "order": "713" }
}
```

The object above shows the Paykit term values an issuer supplies; Paykit owns their wire serialization.

## Payment endpoint

For every advertised identifier, the endpoint payload is a JSON object. `value` is a required, non-empty string:

```json
{"value":"bcrt1qissuerfixture"}
```

Optional `min` and `max` string fields are retained:

```json
{"value":"lnbc1issuerfixture","min":"1000","max":"2000"}
```

Bitkit trims whitespace around the payload and `value`. It rejects a bare address or invoice string, invalid JSON, a non-object top level, a missing `value`, a non-string `value`, non-string `min` or `max` values, an empty value, a whitespace-only value, a wrong-network on-chain identifier, or an unknown identifier.

After this shape check, Bitkit validates that the value is usable: an on-chain address matches the current network, a BOLT 11 invoice is unexpired and network-correct, and an LNURL value is an LNURL-pay request.

## Delivery prerequisites

The issuer and wallet identities must be linked Paykit peers before Bitkit polls the request. Requests may require a specific payment App; Bitkit uses the SDK's request-specific resolver and preserves the selected App in its proof. The issuer can include exact `paymentEndpoints` in the Payment Request or supply current endpoints resolved for the requested App. At least one endpoint must be usable for an identifier retained from the request; embedded endpoints do not require separate advertisement. A request that fails the request gate is not presented; a request whose endpoint cannot be resolved is deferred until usable payment details arrive.

## Contract fixtures

The fixture file is the cross-platform source of truth for Bitkit iOS and Android:

- Request fixtures cover every documented P2TR, P2WPKH, P2SH, and P2PKH identifier for Bitcoin, testnet, signet, and regtest.
- Request fixtures cover both Lightning identifiers on every network.
- Rejected request fixtures cover uppercase `BTC`, a foreign-network on-chain identifier on every network, an uppercase identifier, an unknown identifier, and an empty identifier list.
- Endpoint fixtures accept JSON object payloads with a non-empty string `value`, including optional string bounds and surrounding whitespace.
- Rejected endpoint fixtures cover a raw string, empty payload, missing/empty/whitespace/numeric `value`, non-string bounds, a wrong-network on-chain identifier, top-level array, malformed JSON, and unsupported identifier.

The iOS fixture suite in [PR #719](https://github.com/synonymdev/bitkit-ios/pull/719) consumes the same fixture names, inputs, and expected results. Any intentional platform difference requires changing this contract and both fixture suites together.
