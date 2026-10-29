# Primary node

Lightning-first wallet flows through a primary node: a Lightning node that opens a channel just in time for the
first receive, grows it by splicing, and funds on-chain sends from it by splicing out.

Preconditions for every journey here:

- A regtest build with `PRIMARY_NODE_URI` set in `local.properties` to a primary on the same chain, for example a
  Beignet node with its liquidity provider on (`liquidityProvider`, `waiveClientReserve`) and a funded on-chain wallet.
- Regtest fees at 1 sat/vB: a Beignet primary opens just-in-time channels at a fixed 253 sat/kw commitment fee
  (coreyphillips/beignet#1873), which ldk-node refuses once its fee floor is higher.
- A second Lightning wallet with a channel into the primary pays the invoices and receives the sends.

Both apps run these journeys with the same file names.
