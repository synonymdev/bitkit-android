# Earn receiving-details claims

Earn services request receiving details through Pubky Auth. USDT sharing is optional and uses the existing Arbitrum One account; it creates no account, enables no public-sharing setting, and grants no spending authority. Services see that address's public balance and history.

Use exactly one `x-bitkit-claim` parameter containing a dot-separated list of independent permissions:

| Permission | Shared authority/details |
| --- | --- |
| `watch-only-account-v1` | Separate native-SegWit Bitcoin account |
| `paykit-access-v1` | Generation-bound Paykit identity secret, never a wallet spending key |
| `usdt-address-v1` | Existing USDT0 Arbitrum One address, if approved |

For example, `paykit-access-v1.watch-only-account-v1.usdt-address-v1` requests all three. Preserve the exact received order for signature and channel derivation. Unknown, duplicate or empty items are rejected. Requests require `/pub/paykit/:rw`.

The existing Earn introduction leads to authorization review. USDT has an optional sharing toggle and expandable/copyable address. If loading fails, the user may retry or authorize without USDT. When selected, Bitkit re-reads the address and checks it matches the reviewed address. Canceling before authorization shares nothing. Bitcoin retains its separate account allocation and retry/recovery lifecycle.

## Payload and authentication

Selections containing USDT use a UTF-8 JSON object. Include only requested and approved fields:

- `usdt-arbitrum-address`: `{value, chain_id: "42161", token: "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9"}`. Omit this field when declined.
- `bitcoin_account`: `{account_index, address_type: "nativeSegwit", xpub}` when Bitcoin is requested. No wallet-local names or IDs.
- `paykit_access`: `{key_generation, secret}` when Paykit access is requested. The generation is an exact nonzero unsigned 64-bit integer; the 32-byte secret uses unpadded base64url.

Selections without USDT retain the existing binary layouts: Bitcoin 84 bytes, Paykit 41 bytes, both 124 bytes. Those are independent supported permissions, not USDT compatibility paths.

Paykit signs UTF-8 `x-bitkit-claim|<exact-list>|`, SHA256 of the 32-byte AUTH secret, and the unsigned payload. It appends the 64-byte Ed25519 signature and uses its XSalsa20-Poly1305 companion transport. The relay channel is unpadded base64url of BLAKE3 over UTF-8 `<exact-list>|` followed by the AUTH secret. Verify the received bytes before parsing; never reserialize JSON for signature verification.

The Creator's authenticated Pubky key verifies the claim. This binds receiving details to the identity and request, not ownership of the EVM private key. The server validates the pinned chain/token, address and Bitcoin account before persistence, and verifies delegated Paykit authority against the identity-signed Noise key authorization. A matching server can request USDT only when configured to store and verify it. Declining USDT leaves Bitcoin setup usable. Reconnect may add a missing USDT address while preserving existing receiving bindings.
