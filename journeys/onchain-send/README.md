# On-chain Send Journeys

These journeys verify the user-visible boundary introduced by synonymdev/ldk-node#112:

- `broadcast-accepted.xml` requires explicit backend acceptance before `SendSuccess`.
- `broadcast-rejected.xml` requires a backend rejection to show `OnchainSendFailedToast` without `SendSuccess`.

Run both against an Android build consuming the Maven Local artifact from the exact LDK Node #112
head under validation. The accepted fixture uses a clean funded regtest wallet connected directly to
the local Electrum backend at `tcp://10.0.2.2:60001`.

For the rejected fixture, run:

```sh
node scripts/reject-electrum-broadcast.js
```

The proxy listens on host port `61001`, forwards normal Electrum traffic to host port `60001`, and
returns a deterministic RPC `-26 non-final` rejection for every transaction broadcast. Configure
Bitkit to use `tcp://10.0.2.2:61001` before running `broadcast-rejected.xml`.

The app result is necessary but not sufficient evidence. After each journey, record the transaction
id when present and query the active backend. The accepted transaction must be present in its mempool
or chain. The rejected transaction must be absent, and Bitkit must not create a sent activity for it.

Test tags: `Send`, `RecipientManual`, `RecipientInput`, `AddressContinue`, `send_amount_screen`,
`N1`, `N000`, `ContinueAmount`, `GRAB`, `SendSuccess`, and `OnchainSendFailedToast`.
