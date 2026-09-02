# Paykit issuer interoperability

This is the Bitkit issuer contract for one-time Paykit Payment Requests. It describes the request and payment-endpoint shapes an issuer must provide for Bitkit to present and open a request. The canonical accepted and rejected examples are in
[`app/src/test/resources/paykit-issuer-interoperability.json`](../app/src/test/resources/paykit-issuer-interoperability.json).

This contract records Bitkit behavior. Paykit protocol or SDK policy remains owned by Paykit.

## Payment Request

An actionable request must satisfy all of these requirements:

- The amount asset is exactly lowercase `btc`.
- The amount is a positive decimal Bitcoin value with at most eight significant fractional digits and no more than `18,446,744,073,709,551` satoshis.
- The request is a one-time proposal: the local role is payer, lifecycle state is proposed, and recurrence is absent.
- The proposal expiration is absent or is a valid future ISO 8601 timestamp.
- `acceptedPaymentEndpointIdentifiers` retains at least one identifier supported on the wallet's current network.

Bitkit filters `acceptedPaymentEndpointIdentifiers` in issuer order, removes duplicates after their first occurrence, and drops unknown or wrong-network identifiers. The request remains actionable when at least one identifier survives.

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

Bitkit trims whitespace around the payload and `value`. It rejects a bare address or invoice string, invalid JSON, a non-object top level, a missing `value`, a non-string `value`, an empty value, a whitespace-only value, or an unknown identifier.

After this shape check, Bitkit validates that the value is usable: an on-chain address matches the current network, a BOLT 11 invoice is unexpired and network-correct, and an LNURL value is an LNURL-pay request.

## Delivery prerequisites

The issuer and wallet must be linked Paykit peers on the same receiver path before Bitkit polls the request. The issuer must advertise a usable endpoint for at least one identifier retained from the request. A request that fails the request gate is not presented; a request whose endpoint cannot be resolved is deferred until usable payment details arrive.

## Contract fixtures

The fixture file is the cross-platform source of truth for Bitkit iOS and Android:

- Request fixtures cover the network-correct P2WPKH identifier for Bitcoin, testnet, signet, and regtest.
- Request fixtures cover both Lightning identifiers on every network.
- Rejected request fixtures cover uppercase `BTC`, a foreign-network on-chain identifier on every network, an uppercase identifier, an unknown identifier, and an empty identifier list.
- Endpoint fixtures accept JSON object payloads with a non-empty string `value`, including optional string bounds and surrounding whitespace.
- Rejected endpoint fixtures cover a raw string, empty payload, missing/empty/whitespace/numeric `value`, top-level array, malformed JSON, and unsupported identifier.

The iOS fixture suite in [PR #719](https://github.com/synonymdev/bitkit-ios/pull/719) consumes the same fixture names, inputs, and expected results. Any intentional platform difference requires changing this contract and both fixture suites together.
