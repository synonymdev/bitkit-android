# Local-only Paykit fallback

Status: **unrun**. The default is a purchase on `https://shop.staging.pubky.app` with its deployed
Paykit service. Run this companion only after an actual staging Paykit pairing attempt fails and
its URL, timestamp, HTTP outcome and wallet error have been recorded. A pending signup prerequisite,
unknown server version, invoice-delivery failure or unpaid Shop order does not trigger fallback.

This exercises an operator-owned Paykit server with staging identities and staging regtest Bitcoin.
It does not replace the deployed Shop backend. Standalone pairing, request delivery, accepted payment
and server observation are separate results; none proves that the original Shop order is paid or
that Locks released its content. Follow `local-paykit-fallback.xml` after this setup.

## Prerequisites

- Confirm the homeserver fix PR is open and signup has been rerun successfully against
  `ufibwbmed6jeq9k4p583go95wofakh9fwpp4k734trq79pd9u1uy` before starting this fixture.
- Use two isolated wallets, fresh seller/buyer staging invites and Bitkit-owned identities registered
  on that homeserver. Preserve existing identities. Use the grant signup journey for a fresh
  `pubkyauth://signup_grant` URL naming that homeserver; invites and authorization URLs are secrets.
- Record the app commit, APK hash and installed package. Android uses `devDebug`, `to.bitkit.dev`,
  Paykit `0.1.0-rc72` and native LDK `0.7.0-rc.71`. Use a normal build (`E2E=false`) or explicitly
  network-backed E2E build; the local E2E default selects a different chain endpoint.
- Both wallets and the server must use `ssl://electrs.bitkit.stag0.blocktank.to:9999`, the regtest
  endpoint in `app/src/main/java/to/bitkit/env/Env.kt`. Verify this before funding or creating a request.
- Provide an owned disposable PostgreSQL database, an operator-controlled secret store, and a fixture
  service signer with its exact public key allowlisted. Never point migrations at staging/shared
  databases. The server automatically applies migrations at startup.
- A signed fixture driver must create and observe one request using the pinned server's documented
  routes and signature contract. Record its source revision. Without that driver, stop at pairing;
  do not invent unsigned invoice endpoints or borrow deployed service signing secrets.

## Pinned server and configuration

Use [`pubky/paykit-server` tag `v0.1.0-rc11`](https://github.com/pubky/paykit-server/tree/v0.1.0-rc11),
commit `662dca0619a9aa2962bcd677bd5ddd4563cd2784`. Its `Cargo.toml` pins Paykit to
`ad3c72248d18587bb5b6ef3c99b063fa9bf31551`, matching client rc72. A matching pin is not pairing evidence.
Verify `git rev-parse HEAD` in the server checkout before running it.

Copy `config/paykit-server.example.toml` from that revision to an operator-controlled file and set:

| Key | Value |
| --- | --- |
| `http.listen_addr` | `127.0.0.1:8080` on the fixture host |
| `http.trusted_proxy_hops` | `0`; do not trust forwarded headers without verifying the proxy chain |
| `signed_services.trusted_public_keys` | Nonempty list containing only the fixture service's canonical public key |
| `setup.allowed_origins` | Exact HTTPS origin of the fixture's requesting page; no wildcard |
| `setup.log_authorization_url` | `false` |
| `paykit.client_id` | `app.paykit.server` |
| `paykit.app_id` | `paykit-server` |
| `paykit.network` | `mainnet` |
| `bitcoin.network` | `regtest` |
| `electrum.endpoint` | `ssl://electrs.bitkit.stag0.blocktank.to:9999` |

Retain the example's remaining supported keys. `paykit.network = "mainnet"` means normal Pkarr and
homeserver resolution, including the staging identities above; it does not select Bitcoin mainnet.
`"testnet"` instead selects fixed localhost Pubky services and is wrong for this fixture. The server
accepts no arbitrary homeserver/relay config keys: select the homeserver during identity signup.

Supply `PAYKIT_CONFIG` (file path), `PAYKIT_DATABASE_URL` (owned database) and `PAYKIT_MASTER_KEY`
(exactly 32 bytes encoded as unpadded base64url) through the secret store, not command arguments,
source files or captured logs. From the pinned server checkout, the documented commands are:

```sh
cargo run --locked -p paykit-server -- --check-config
cargo run --locked -p paykit-server
```

The first command must print `configuration valid`; it does not connect to the database or prove
runtime readiness. In a separate owned process, expose only this listener:

```sh
cloudflared tunnel --url http://127.0.0.1:8080
```

Record the assigned HTTPS tunnel origin and exact process identities. Require successful
`GET /health/live` and `GET /health/ready` through the tunnel before pairing. A changed tunnel URL
requires updating the fixture URL; do not restart the server or reset identities to retry a payment.

Open the tunnel's `/setup?return_to=<percent-encoded-allowed-return-URL>&state=<fresh-state>` in the
fixture page. Upstream rc11 initial setup accepts only `return_to` and `state`, not `creator`.
The page supplies the real Pubky Auth QR/deep link and polls `POST /setup/{flow_id}/complete`.
Approve its Paykit and watch-only account claims in the seller wallet. Existing stored bindings use
`/setup/reconnect` with `creator`, `return_to` and `state`; do not replace or reset the binding.
Do not change the deployed Shop configuration to point at this tunnel.

## Payment and evidence

Enable contact payments and save both contacts as in `wallet-leg.xml`. Fund only the fresh buyer's
verified staging-regtest savings address. The repository's staging-default `lsp` helper supports:

```sh
./lsp POST /regtest/chain/deposit '{"address":"<buyer-savings-address>","amountSat":100000}'
./lsp POST /regtest/chain/mine '{"count":1}'
```

Wait for the confirmed balance, then create one small request within it. Freeze the original buyer,
seller, request ID, fixture invoice/bundle ID, amount, network, derived recipient and deadline before
authentication. Keep the failed remote Shop order separately identified; a fixture invoice is not a
replacement order. Capture the original selected inputs, signed transaction ID and retained-receipt
hash where available, without logging raw secrets. On uncertainty, retain the original request and
payment guard; any authenticated retry must preserve the original amount, inputs and retained bytes.
Never substitute payer, endpoint, order, request or inputs, or use absence of evidence to unlock.

Record wallet acceptance, the exact transaction/output on staging regtest, server delivery/payment
status and confirmation height separately. `connected` alone is not payment completion. Preserve
sanitized evidence of each boundary; omit invites, auth URLs, private keys, sessions and signatures.

## Failure classification and teardown

Report the first failed boundary: `signup prerequisite`, `config/startup`, `tunnel/transport`,
`pairing/claim`, `contact/link`, `request delivery`, `wallet pre-dispatch`, `broadcast rejected`,
`broadcast unknown`, `proof delivery`, `server observation`, or `Shop order completion`.
Capture the timestamp, original IDs, HTTP status/request ID and typed error where available.
Do not relabel a transport/storage error as revoked authority or a transaction ID as acceptance.
If no Shop order was exercised, report Shop acceptance **unrun**, not failed or passed.

Save evidence before stopping owned server/tunnel processes and test devices. Verify their exact
process/device identities and shutdown; never stop a shared database, adb server or another fixture.
Retain the owned database and original recovery state while a payment outcome is unresolved, with
its location and owner recorded privately. Remove disposable binaries/browser profiles and temporary
files only after evidence and required recovery state are secured. Record every incomplete step.
