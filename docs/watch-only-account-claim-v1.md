# Bitkit watch-only account claim v1

This document records the client contract implemented by Bitkit iOS and Android for Paykit Server setup requests.

## Request

- The Pubky Auth URL includes `x-bitkit-claim=watch-only-account-v1`.
- The exact capability is `/pub/paykit/v0/bitkit/server/:rw`.
- Missing, unknown, mismatched, or duplicate companion-claim parameters are rejected.
- Every distinct auth request creates a fresh native-SegWit account, beginning at BIP84 account index `1`. Account indexes increase monotonically and are never reused. Retrying the same logical auth request reuses its incomplete account even if query parameters are reordered.
- Bitkit automatically names the account from the requesting service. The user can rename it later. The local name is not disclosed in the claim.

## Claim payload

Bitkit serializes this exact 84-byte unsigned payload:

| Offset | Size | Value |
| --- | ---: | --- |
| 0 | 1 | Claim version, `0x01` |
| 1 | 4 | BIP account index, unsigned big-endian |
| 5 | 1 | Address type, `0x00` for native SegWit |
| 6 | 78 | Base58Check-decoded extended public key, including its 4-byte version |

Bitkit passes the payload to Paykit's `approveAuthWithCompanionClaim` API. Paykit appends a 64-byte Ed25519 signature, encrypts the resulting 148-byte claim, delivers it on the companion relay channel, and only then approves normal Pubky Auth.

The signature input is the byte concatenation:

```text
UTF8("x-bitkit-claim|watch-only-account-v1|")
|| SHA256(decoded_auth_request_secret)
|| claim_bytes[0..<84]
```

`decoded_auth_request_secret` is the raw 32-byte value produced by base64url-no-pad decoding the URL's `secret` parameter, not UTF-8 text.

The server verifies the signature with the creator's Pubky Ed25519 public key from the authenticated session. Binding the signature to the request secret prevents a valid signed claim from being moved to a different request; possession of the relay secret alone is insufficient to substitute an attacker's xpub.

## Delivery and lifecycle

- The normal AuthToken channel is `base_relay/{base64url_no_pad(BLAKE3(secret))}`.
- The companion channel is `base_relay/{base64url_no_pad(BLAKE3(ASCII("watch-only-account-v1|") || secret))}`.
- Paykit encrypts the complete 148-byte signed claim on the companion channel with the auth request secret using XSalsa20-Poly1305.
- Paykit delivers the claim before approving the normal Pubky Auth token, avoiding a session that was authorized without its required account claim.
- Bitkit persists the account before delivery and reuses the same account index and unsigned xpub payload when retrying an incomplete setup. Each attempt may create new encrypted relay messages; delivery is not guaranteed exactly once.
- Bitkit durably marks and loads an incomplete account as authorizing before calling Paykit. Successful combined approval marks it active and leaves tracking enabled. An initial preparation or companion-delivery failure returns it to pending and unloads it again.
- If Paykit reports that companion delivery succeeded but normal AuthToken delivery failed, Bitkit leaves the account authorizing and tracked. The same conservative state is retained if local activation persistence fails after Paykit returns success. Retrying reruns the combined Paykit approval with the same account and xpub payload; retry failures keep the account tracked so Bitkit does not lose visibility into addresses the server may already have derived.
- Disabling tracking unloads the account from LDK Node at runtime. It does not delete persisted wallet state, the xpub, or the server session.
- Enabled active or authorizing accounts are configured before LDK Node starts. Electrum full scans use a batch size of `100` and stop gap of `1000`.
- Bitkit pre-reveals external receive indexes `0...999` for each tracked account. LDK then maintains a rolling stop-gap window: the first address with transaction history must be at or below index `999`, and after activity at index `n`, the next active index must be at or below `n + 1000` so there are never `1000` consecutive inactive addresses.
- On startup and before app-driven sync, Bitkit reconciles persisted account state with LDK and restores the pre-revealed range. Accounts removed by a backup remain scheduled for unload until reconciliation succeeds, allowing transient failures to retry safely.
- Account metadata and monotonic allocation state are included in the existing encrypted wallet backup and use the same JSON field names on iOS and Android.
