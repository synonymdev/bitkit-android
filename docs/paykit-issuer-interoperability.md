# Paykit Issuer Interoperability

Paykit issuers must use the following shapes for payment requests and endpoint payloads that Bitkit can open.

## Payment requests

- Set `terms.amount.asset` to the exact lowercase value `btc`.
- Advertise at least one endpoint identifier supported by the active Bitkit network.
- Lightning identifiers are chain-independent:
  - `btc-lightning-bolt11`
  - `btc-lightning-lnurl`
- On-chain identifiers are chain-specific and use `btc-<network>-<script>`.

| Bitkit network | P2TR | P2WPKH | P2SH | P2PKH |
| --- | --- | --- | --- | --- |
| Bitcoin | `btc-bitcoin-p2tr` | `btc-bitcoin-p2wpkh` | `btc-bitcoin-p2sh` | `btc-bitcoin-p2pkh` |
| Testnet | `btc-testnet-p2tr` | `btc-testnet-p2wpkh` | `btc-testnet-p2sh` | `btc-testnet-p2pkh` |
| Signet | `btc-signet-p2tr` | `btc-signet-p2wpkh` | `btc-signet-p2sh` | `btc-signet-p2pkh` |
| Regtest | `btc-regtest-p2tr` | `btc-regtest-p2wpkh` | `btc-regtest-p2sh` | `btc-regtest-p2pkh` |

Bitkit ignores unsupported identifiers and on-chain identifiers for another network. A request with no supported identifier is not actionable.

## Endpoint payloads

Encode each endpoint payload as a JSON object with a non-empty string in `value`:

```json
{"value":"bcrt1qexample"}
```

Optional `min` and `max` values are strings:

```json
{"value":"lnbcrt1example","min":"1000","max":"2000"}
```

Bitkit trims surrounding whitespace from `value`. It rejects bare values, JSON strings, missing values, non-string values, and empty values.

## Compatibility fixtures

For every Bitkit network, issuer fixtures must prove that:

- both Lightning identifiers are accepted;
- all four on-chain identifiers for the active network are accepted;
- on-chain identifiers for the other networks are rejected;
- lowercase `btc` is accepted and other casing is rejected;
- JSON objects containing a non-empty string `value` are accepted;
- bare, missing, non-string, and empty endpoint values are rejected.
