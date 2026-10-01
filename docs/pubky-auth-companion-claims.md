# Bitkit Pubky Auth companion claims

Bitkit can authorize a Pubky session and share Paykit access, a watch-only Bitcoin account, or both. The request explicitly selects the material to share; homeserver write permissions alone never imply key export.

## Request

- The Pubky Auth URL includes one `x-bitkit-claim` query parameter containing a dot-separated list of independent items: `paykit-access-v1` and `watch-only-account-v1`. Each item requests only its corresponding permission and material.
- Request builders emit Paykit first when requesting both: `x-bitkit-claim=paykit-access-v1.watch-only-account-v1`. Paykit Server requests both items for initial setup and only `paykit-access-v1` for reconnect.
- Either item order is accepted. Bitkit preserves the exact received list string as the SDK's `claim_type`; it must not be reordered before signing or relay delivery.
- The capability is `/pub/paykit/:rw`.
- Empty, unknown, or duplicate items, mismatched selections, and duplicate companion query parameters are rejected. Ordinary Pubky Auth without a companion claim shares only the session, not a Paykit access key or watch-only account.
- Explicit watch-only approval creates a fresh native-SegWit account. Account indexes begin at `1`, increase monotonically, and are never recycled. Retrying the same logical auth request reuses its incomplete account even if query parameters are reordered.
- Bitkit automatically names the account from the requesting service. The user can rename it later. The local name is not disclosed in the claim.
- Paykit-only reconnect leaves local watch-only accounts unchanged. The server retains its existing xpub, account index, and allocation state without requesting the account again; it binds the reconnect to the expected authenticated Pubky identity.
- Paykit-only approval neither allocates nor loads a Bitcoin account. It opens authorization directly, explaining private Paykit data and messages without watch-only or content-earning UI.

## Claim payload

The watch-only unsigned payload is exactly 84 bytes:

| Offset | Size | Value |
| --- | ---: | --- |
| 0 | 1 | Claim version, `0x01` |
| 1 | 4 | BIP account index, unsigned big-endian |
| 5 | 1 | Address type, `0x00` for native SegWit |
| 6 | 78 | Base58Check-decoded extended public key, including its 4-byte version |

The Paykit-only unsigned payload is exactly 41 bytes: version `1`, an 8-byte nonzero unsigned big-endian key generation, and the 32-byte Paykit access key. Requesting both items produces exactly 124 bytes: the 84-byte watch-only payload followed by that generation and key, without another version byte. This payload order is fixed regardless of the requested item order.

Bitkit passes the exact item list as `claim_type` and the payload to Paykit's `approveAuthWithCompanionClaim` API. Paykit appends a 64-byte Ed25519 signature, encrypts the signed claim, delivers it on the companion relay channel, and only then approves normal Pubky Auth. The signed sizes are respectively 148, 105, and 188 bytes.

For requests including Paykit access, Bitkit derives the Paykit secret from its local or shared Pubky identity secret and the current App Registry generation. A locally recorded generation prevents rollback. The recipient derives the separate Noise and shared-state encryption keys from the delegated secret. Neither the Pubky root secret nor Bitcoin spending keys are shared. Watch-only requests do not derive or send a Paykit secret.

The signature binds the UTF-8 prefix `x-bitkit-claim|`, the exact received item list and trailing `|`, the SHA256 digest of the decoded auth request secret, and the complete unsigned claim bytes, concatenated in that order. Changing the list order changes both the signature domain and relay channel even though the unsigned payload is identical. Each selection requires its exact payload length: 84 bytes for watch-only, 41 bytes for Paykit access, and 124 bytes for both.

`decoded_auth_request_secret` is the raw 32-byte value produced by base64url-no-pad decoding the URL's `secret` parameter, not UTF-8 text.

The server verifies the signature with the creator's Pubky Ed25519 public key from the authenticated session. Binding the signature to the request secret prevents a valid signed claim from being moved to a different request; possession of the relay secret alone is insufficient to substitute an attacker's xpub.

## Delivery and lifecycle

- The normal AuthToken channel is `base_relay/{base64url_no_pad(BLAKE3(secret))}`.
- The companion channel is `base_relay/{base64url_no_pad(BLAKE3(UTF8(claim_type || "|") || secret))}`.
- Paykit encrypts the complete signed claim on the companion channel with the auth request secret using XSalsa20-Poly1305. The SDK owns transport and cryptography.
- Paykit delivers the claim before approving the normal Pubky Auth token, avoiding a session that was authorized without its required account claim.
- Bitkit persists the account before delivery and reuses the same account index and unsigned xpub payload when retrying an incomplete setup. Each attempt may create new encrypted relay messages; delivery is not guaranteed exactly once.
- Bitkit durably marks and loads a new incomplete account as authorizing before calling Paykit. Successful approval marks it active and leaves tracking enabled. An initial preparation or companion-delivery failure returns it to pending and unloads it again.
- Account registration and address revelation finish before authorization. LDK's periodic sync fetches transaction history; a full wallet sync is not a prerequisite for delivering the authorization.
- If Paykit reports that companion delivery succeeded but normal AuthToken delivery failed, Bitkit leaves the account authorizing and tracked. The same conservative state is retained if local activation persistence fails after Paykit returns success. Retrying reruns the combined Paykit approval with the same account and xpub payload; retry failures keep the account tracked so Bitkit does not lose visibility into addresses the server may already have derived.
- Disabling tracking unloads the account from LDK Node at runtime. It does not delete persisted wallet state, the xpub, or the server session.
- Enabled active or authorizing accounts are configured before LDK Node starts. Electrum full scans use a batch size of `100` and stop gap of `1000`.
- Bitkit pre-reveals external receive indexes `0...999` for each tracked account. LDK then maintains a rolling stop-gap window: the first address with transaction history must be at or below index `999`, and after activity at index `n`, the next active index must be at or below `n + 1000` so there are never `1000` consecutive inactive addresses.
- On startup and before app-driven sync, Bitkit reconciles persisted account state with LDK and restores the pre-revealed range. Accounts removed by a backup remain scheduled for unload until reconciliation succeeds, allowing transient failures to retry safely.
- Account metadata and monotonic allocation state are included in the existing encrypted wallet backup and use the same JSON field names on iOS and Android.

## Shared fixtures and consent

The canonical server fixture `bitkit-combined-claim-v1.json`, also included in Android test resources, defines the combined wire layout and exact bytes.

The shared UI identifiers are `PubkyAuthPaykitAccess` for private Paykit consent and `PubkyAuthWatchOnlyConsent` for the original wallet introduction. Requested Paykit access appears only on final authorization. Journey specifications cover visible consent and cancellation separately from fixture-backed delivery and Paykit-only reconnect.
