# USDT configuration

Normal builds use published bitkit-core `0.8.0-rc2`, including its matching native libraries and generated bindings.

USDT uses Arbitrum One regardless of the Bitcoin network setting. Gradle reads `USDT_RPC_URL` and `USDT_BUNDLER_URL` from local properties/environment configuration. Both must point to the controlled service's credential-free HTTPS chain and bundler routes. Missing endpoints hide the wallet entry.

Optional `USDT_DEPOSITS_URL` enables wallet-signed Orchestra deposits. Provider keys remain on the service. Source support covers Ethereum, Tron, Solana, Polygon, Base and BNB Smart Chain, subject to explicit service enablement and live route availability. Each source requires funded acceptance before release. Users approve a source-network refund address when requesting an eligible refund; unconverted Tron refunds require provider support. History and addresses recover under the same provider partner account. Linked order amounts are batch totals. Outbound USDT0 destinations are enabled with `USDT_BRIDGE_NETWORKS`, a comma-separated subset of `ethereum,polygon,plasma,stable`, matching the service configuration. Empty configuration offers Arbitrum only. Paykit always uses direct Arbitrum payments.

The `bitkitCoreAar` Gradle property selects an absolute local release AAR. Bindings and native libraries must come from the same core build.

For a local emulator gateway, use loopback URLs and ADB reverse port forwarding. Core allows HTTP only on localhost or 127.0.0.1; the emulator host alias 10.0.2.2 requires HTTPS.

USDT follows the existing UI → ViewModel → repository → Rust ownership. The repository manages foreground refresh, receive/pending refresh and rate-limit backoff. Native payment authentication precedes signing. Transaction IDs and explorer links appear when a transaction hash is available; Arbiscan links are built by the app. Failed/replaced payment details do not claim recipient delivery. Wallet wipe closes core handles before deleting wallet-specific USDT files.

Protocol and recovery behavior belong to bitkit-core's USDT module documentation; provider deployment belongs to the service documentation.

History catch-up is deferred while the USDT send sheet is open. Balance and pending-payment recovery continue; a history scan already in progress is allowed to finish. Unknown deposit-provider statuses require attention instead of appearing as ordinary pending deposits. Cross-network payment details label the recipient amount as expected until delivery is confirmed.

## Paykit

Paykit uses a direct Arbitrum USDT0 endpoint (`usdt-arbitrum-address`). Existing public/private sharing settings control publication. The payload binds the address, chain 42161 and pinned token; bridge routes are excluded from Paykit choices.

Bitkit creates BTC or USD requests and also reads USDT-denominated requests from other wallets. The accepted methods are immutable request terms. Every accepted cross-asset currency has an explicit requester-supplied rate; the denominating asset needs no conversion. USD/USDT is exactly 1:1. Bitcoin rates come from the existing BTC/USD source when issuing terms, not when verifying received funds. External requests without rates allow only same-asset payment. A listed method without its required rate cannot be used.

Required amounts are rounded upward once to Bitkit's payment precision (satoshis for Bitcoin and six decimal places for USDT). Fees are additional. Approval pins the amount, quote identifier and validity interval; changes requiring new approval return through review. Receipt validation uses those terms with no percentage tolerance or later market repricing.

One-time requests use the selected expiry as both the acceptance limit and actual payment deadline. Bitkit-created subscriptions accept only enabled Bitcoin methods (Savings/Spending) for BTC amounts, or USDT for USD amounts. USD subscriptions include a fixed 1:1 USDT rate for every installment; BTC subscriptions need no conversion. No recurring quote publisher or requester availability is needed to price later installments. External per-period quotes and absolute or period-relative deadlines are still honored, and existing subscription terms and proof history are not rewritten. Each installment requires payer approval; accepting a subscription does not authorize automatic payments.

Before sending USDT, the app durably associates the request and billing period with the core payment ID and immutable proof binding. After successful execution, core signs the released `erc20-transfer-eip712` proof over both participants, the receiving payment app, request reference, endpoint, billing period and selected quote. The full proof envelope is size-checked before sending. Retries and restarts deliver the same proof without another transfer; a replacement payment is allowed only for an unstarted attempt without a core operation or a definitively failed/replaced operation. Wallet backups retain these associations, started-payment markers, received-payment identities and selected conversion quotes; missing execution history after restore never authorizes a replacement payment.

Receipts require successful canonical execution, the correct chain/token/recipient and the transfer sender's signature. A payment is identified by verified chain, transaction and original receipt-log position and can satisfy at most one request/period. Real funds are displayed as received, underpaid or after expiry, with their actual amount. Overpayment is accepted; underpayment and late payment do not start a dispute, refund or top-up workflow. Unavailable evidence remains pending; a previously verified receipt is rechecked without discarding its attribution. A submitted USDT proof remains visible while verification is pending; only a verified, sufficiently funded, timely receipt advances a received subscription's paid count and paid-through date. Bitcoin retains its existing settlement behavior.

## Encrypted recovery backups

The wallet backup includes core's portable USDT recovery snapshot alongside Paykit request bindings, pending proofs and received-payment identities. It contains signed operations and stable activity IDs, but no private keys or seed. The existing VSS client encrypts and authenticates the complete envelope. History cursors and unsigned quotes are rebuilt locally.

Core waits for the app to acknowledge a VSS upload before first submission or automatic rebroadcast. If backup is unavailable, the signed payment stays pending locally and no submission is attempted; recovery retries the same operation once backup is available, or reconciles its expiry against the chain. Wallet uploads are serialized so an older background snapshot cannot replace a payment's acknowledged backup.

Restore merges records for the derived account without overwriting newer local outcomes. Signed operations imported into an empty database are reconciled against chain evidence before retry; already discovered transactions retain their restored application IDs. A failed restore retains the downloaded envelope and blocks replacement backups. Bitcoin-only backups remain readable. Seed-only recovery restores account control and chain history, but cannot reconstruct a never-mined signed operation or its Paykit attribution. Missing evidence never authorizes a second payment.

## Outbound provider selection

Configure `USDT_BRIDGES_URL` with the gateway's credential-free `/v1/usdt/bridges` route to enable Orchestra. The network selector combines the gateway's enabled Orchestra destinations with the app's `USDT_BRIDGE_NETWORKS` USDT0 destinations. Direct Arbitrum and Paykit remain unchanged. Core chooses the best usable estimated receipt per maximum total source debit, including the source paymaster fee; a tie prefers USDT0.

Orchestra routing costs are deducted from the entered USDT principal. Review shows estimated destination receipt, included route cost, additional maximum source fee and maximum total debit. Fees remain in USDT, including when the provider uses intermediate assets. The chosen quote is frozen before signing. Funding and delivery are separate: activity retains the original recipient and network through pending delivery, provider attention and a verified Arbitrum refund. A timeout, restart or provider failure never starts a payment through another provider. Encrypted backup includes the funding plan and tracking ticket; seed-only history cannot reconstruct them.
