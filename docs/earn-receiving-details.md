# Earn receiving-details claims

Bitkit lets an Earn service request a Bitcoin watch-only account, the existing USDT address on Arbitrum One, or both through the existing Pubky Auth approval flow. This contract covers address sharing, not USDT invoicing, payment verification or purchase delivery.

## Request and approval

Use exactly one `x-bitkit-claim` query parameter:

| Claim | Shared details |
| --- | --- |
| `watch-only-account-v1` | A separate native-SegWit Bitcoin account, using the existing 84-byte payload |
| `usdt-address-v1` | The existing USDT Arbitrum receiving endpoint |
| `payment-details-v1` | Both that endpoint and a separate Bitcoin account |

All three require exactly `/pub/paykit/:rw`. Unknown or repeated claim parameters and mismatched capabilities are rejected. Changing the claim changes the request identity and companion channel.

The user first sees the existing Earn introduction, then the authorization review showing the requested assets. The USDT address is expandable and copyable. Approval is unavailable until the address can be read, and Bitkit re-reads it before sending to ensure it is the address reviewed. Canceling before authorization shares nothing and allocates no Bitcoin account.

USDT sharing creates no account, changes no public-sharing preference and gives no permission to spend. The receiver can observe the account's public balance and transaction history. Different services receive the same USDT address. Bitcoin sharing retains its separate account allocation and tracked retry/recovery lifecycle.

## Payload

For `usdt-address-v1`, the unsigned payload is a UTF-8 JSON object with exactly the `usdt-arbitrum-address` key. Its endpoint contains string fields `value` (the EVM address), `chain_id` (`42161`) and `token` (`0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9`).

For `payment-details-v1`, the unsigned JSON additionally contains `bitcoin_account`, with `account_index` (an unsigned integer), `address_type` (`nativeSegwit`) and `xpub` (Base58Check text). Wallet-local names and identifiers are not shared. The xpub's version identifies its Bitcoin network; the server must check it against its configured network.

JSON key order is not significant. Verify the signature over the received bytes before decoding; do not reconstruct or reserialize the object for verification. The existing `watch-only-account-v1` binary format is unchanged.

## Signed delivery

Use Paykit's companion-claim approval API with the exact selected claim name. The signature input is UTF-8 `x-bitkit-claim|<claim>|`, followed by SHA256 of the decoded 32-byte auth request secret, followed by the unsigned payload bytes. Paykit appends the 64-byte Ed25519 signature and encrypts the signed payload using the existing XSalsa20-Poly1305 envelope.

The companion channel is base64url-no-pad of BLAKE3 over UTF-8 `<claim>|` followed by the decoded request secret. The normal AuthToken channel is unchanged. Claim delivery precedes normal auth approval. The signature is verified against the creator's authenticated Pubky public key. It binds the receiving details to that identity and request, not to ownership of an EVM private key.

## Server integration

Paykit Server's current implementation accepts only `watch-only-account-v1`. Supporting the new claim names requires using the requested claim name for channel derivation and signature verification, parsing the appropriate payload after verification, validating the pinned endpoint and optional Bitcoin account, and durably storing the selected receiving details before acknowledging setup. Existing Bitcoin setup and stored credentials must remain readable; neither USDT-only sharing nor combined sharing authorizes deleting them.

The server must not treat a shared USDT address as an enabled USDT checkout method. USDT invoice creation and payment verification are separate work. A server should issue these new claim requests only when it can accept and store them. Bitkit should not advertise a completed production Earn integration until that server behavior is available and tested together.
