# Bitkit watch-only account claim v1

This document records the client contract implemented by Bitkit iOS and Android for Paykit Server setup requests.

## Request

- The Pubky Auth URL includes `x-bitkit-claim=watch-only-account-v1`.
- The exact capability is `/pub/paykit/v0/bitkit/server/:rw`.
- Every distinct auth request creates a fresh native-SegWit account, beginning at BIP84 account index `1`. Retrying the same auth URL reuses its pending account.
- The user assigns a local name to the account. The name is not disclosed in the claim.

## Signed claim

The decrypted claim is 148 bytes:

| Offset | Size | Value |
| --- | ---: | --- |
| 0 | 1 | Claim version, `0x01` |
| 1 | 4 | BIP account index, unsigned big-endian |
| 5 | 1 | Address type, `0x00` for native SegWit |
| 6 | 78 | Base58Check-decoded extended public key, including its 4-byte version |
| 84 | 64 | Ed25519 signature |

The signature input is the byte concatenation:

```text
UTF8("x-bitkit-claim|watch-only-account-v1|")
|| SHA256(UTF8(decoded_auth_request_secret))
|| claim_bytes[0..<84]
```

The server verifies the signature with the creator's Pubky Ed25519 public key from the authenticated session. Binding the signature to the request secret prevents a valid signed claim from being moved to a different request; possession of the relay secret alone is insufficient to substitute an attacker's xpub.

## Delivery and lifecycle

- The normal AuthToken channel is `base_relay/{base64url_no_pad(BLAKE3(secret))}`.
- The companion channel is `base_relay/{base64url_no_pad(BLAKE3(ASCII("watch-only-account-v1|") || secret))}`.
- Bitkit encrypts the complete 148-byte signed claim on the companion channel with the auth request secret using the existing XSalsa20-Poly1305 format.
- Bitkit delivers the claim before approving the normal Pubky Auth token, avoiding a session that was authorized without its required account claim.
- Bitkit persists the account before delivery and retries the same pending claim idempotently.
- Disabling tracking rebuilds LDK Node without registering that account. It does not delete the xpub or revoke the server session.
- Account metadata is included in the existing encrypted wallet backup and uses the same JSON field names on iOS and Android.

## Required shared protocol work

The current Paykit app bindings do not expose the companion encrypted-relay operation, so both clients deliberately fail closed at delivery. Paykit must provide one shared binding that derives the domain-separated channel, encrypts, and posts the signed claim; duplicating Pubky relay cryptography in each app is not accepted.

The server protocol must also communicate its highest issued external address index. Bitkit can then call LDK Node's account-specific reveal API before syncing. Without that high-water mark, addresses beyond the wallet lookahead cannot be guaranteed to appear, even though initial addresses work normally.
