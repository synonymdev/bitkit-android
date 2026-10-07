# USDT manual integration checks

The journey environment does not provide funded Arbitrum or cross-network accounts, controlled USDT provider/RPC/bundler responses, encrypted VSS outages, or a subscription billing clock. These checks require the named external setup. Record the build, configuration, actual transaction evidence, and whether evidence came from a fixture or a live service. Wallet resets and transfers require the owner’s authorization.

The drivable wallet navigation and request/subscription UI paths are in `journeys/usdt-wallet-navigation.xml` and `journeys/paykit-quoted-usdt.xml`. The manual checks below are specifications, not evidence of execution.

## usdt bridge quotes

A funded Arbitrum wallet uses the real gateway with USDT0 and Orchestra enabled. This journey obtains quotes only; do not swipe to pay or create any funded order.

- [ ] Open USDT from the home balance, then Send. Verify UsdtNetwork offers Arbitrum and the enabled provider destinations.
- [ ] Choose Base, open manual entry and enter an owned EVM address. Enter an affordable USDT amount and continue to review.
- [ ] Verify the review displays Base, the selected Orchestra provider in details, estimated destination receipt, included bridge cost, maximum source fee and maximum total debit. All amounts use USDT.
- [ ] Close without paying. Start a send to Polygon and obtain a quote to an owned address. Verify the selected provider is shown and the same amount and fee fields remain clear.
- [ ] Close without paying. Select Solana and verify an EVM address is rejected, then select Tron and verify an EVM address is rejected.
- [ ] Close Send and verify the home balance has not changed and no outgoing payment was created.

## usdt outbound bridge

USDT is configured against the real gateway; both app and service enable Polygon through USDT_BRIDGE_NETWORKS, and USDT_BRIDGES_URL enables Orchestra destinations. Use a funded wallet and an owned destination address. Mainnet transfers require authorization. Preserve wallet data, keep authentication out of recordings, and record actual transaction hashes and fees.

- [ ] Open the home screen and verify the USDT balance, then open USDT and Send.
- [ ] Choose Polygon in UsdtNetwork and open manual address entry. Verify an invalid address and an Arbitrum-specific payment URI cannot advance.
- [ ] Select Arbitrum and accept an Arbitrum payment URI. Go back from amount entry and change to Polygon. Verify the previous recipient and amount are cleared and must be entered again.
- [ ] Enter the owned destination address and a small USDT amount. Verify the review shows Polygon, the full recipient, selected provider, estimated received amount, included Orchestra routing cost where applicable, additional maximum source fee and maximum total debit in USDT.
- [ ] Authorize one payment. Verify it appears in activity as awaiting cross-network delivery rather than claiming delivery from source-chain execution alone.
- [ ] Restart the app and verify the same transfer continues tracking without sending a second payment.
- [ ] Verify destination delivery independently from its transaction receipt, token, recipient, amount and balance. Confirm activity details update and the Arbitrum wallet retains zero ETH.
- [ ] Return home and verify the updated USDT balance. Verify Paykit payment choices remain Arbitrum-only.

## usdt cross-network receive

Requires a wallet with USDT enabled and a matching backend with Orchestra credentials and accepted source networks enabled. Address and estimate checks do not move funds. Externally funded settlement and provider-held refunds require separate manual tests.

- [ ] Open the USDT wallet and Receive. Verify direct Arbitrum One receiving is available.
- [ ] Open the network selector. Verify only configured supported sources are offered: Ethereum, Tron, Solana, Polygon, Base and BNB Smart Chain.
- [ ] Select each enabled source network, enter an amount within its live limits, and get its deposit address. Verify the selected network, reusable address, estimated received amount and deduction are shown. Verify the QR and Copy action use the same address.
- [ ] Return to Arbitrum One and verify its original wallet address remains unchanged.
- [ ] Open cross-network deposit activity. Verify empty history and Load more work without showing a failure.
- [ ] Enter an amount below the route minimum and verify the limit error appears without a deposit address.
- [ ] Edit the amount. Verify the old limit error clears immediately and the keypad and button remain fully visible. Get an address with an amount within the route limits.
- [ ] Reopen the app and verify the reusable address and deposit history are recovered. Inspect any existing deposit details without claiming a new settlement.

## usdt payment recovery

Two linked wallets with USDT enabled and separately authorized payment funds. Preserve wallet data. This covers normal UI restart recovery; controlled VSS/RPC/bundler outages, missing storage and cross-platform backup injection require separate manual tests.

- [ ] Create a USD subscription payable in USDT and open an installment payment review.
- [ ] Verify recipient, amount and maximum fee, then authorize one installment payment.
- [ ] Once dismissal is permitted, close the payment sheet and reopen the USDT wallet. Record the displayed transfer and request attribution.
- [ ] Restart the app and reopen that transfer. Verify it retains the same activity entry and request attribution without asking for a second payment approval.
- [ ] If the transfer is still pending, try to cancel its subscription. Verify unresolved payment execution prevents cancellation.
- [ ] Refresh until the transfer settles or reports a terminal outcome. Verify its activity details show that outcome and no second outgoing transfer was created.

## Paykit settlement, later installments and evidence

Requires funded Arbitrum accounts, controlled billing time, and controlled receipt evidence, none of which is listed in Capabilities.

- [ ] Open the home screen and verify the USDT balance is visible and a funded USDT wallet does not show empty-wallet onboarding.
- [ ] Continue to review and verify recipient, amount and additional USDT fee before authorizing payment.
- [ ] Complete payment with the receiver viewing Subscriptions and verify success, green receive celebration, activity details and the actual received amount on both wallets.
- [ ] Restart both apps and verify the same payment history and request attribution, with no second payment.
- [ ] Create a BTC request accepting Bitcoin and USDT. Switch the payer between Savings, Spending when enabled, and USDT; verify the requested value is preserved and the quoted amount and fee appear before approval.
- [ ] Using a controlled billing clock, advance to the next installment with the requester offline. Verify its USD amount still maps exactly to USDT, the fee remains additional, and earlier paid periods remain unchanged.
- [ ] Using controlled payment evidence, verify underpayment and payment after expiry show actual received funds and the corresponding status, without reporting the transfer as failed.
- [ ] Pay a contact directly without a request and verify the normal asset selector, send review and activity attribution still work. Verify selecting USDT does not request camera access for an already resolved recipient.

## Backup and dependency failures

Requires disposable wallets and controlled VSS/RPC/bundler/storage endpoints; no Android device harness providing these controls is supplied here.

- [ ] Make VSS unavailable before approving one payment. Verify a visible backup failure and no bundler submission. Restore VSS and verify only the original approved operation can continue.
- [ ] Restore its encrypted envelope on the opposite platform with the same authorized seed. Verify one operation, request attribution and proof delivery, without a second approval or transfer.
- [ ] Restore with unavailable local storage. Verify restore remains incomplete and no replacement wallet backup is uploaded.

## USDT design and receive estimates

Reference: [USDT designs](https://www.figma.com/design/ltqvnKiejWj0JQiqtDf2JJ/Bitkit-Wallet?node-id=48063-274766).

- [ ] Check funded and empty Home and USDT wallet layouts; the USDT accent is #009393 throughout receive, send, Paykit, sharing and celebrations.
- [ ] In Receive, open Network and select an enabled source. Verify the minimum-amount error is compact and clears on editing.
- [ ] Enter an accepted amount, switch BTC/USD twice and verify the amount is preserved. Continue and verify Estimated Fees shows the expected deduction and received amount, then continues to the QR for that network.
- [ ] Open address details, verify Copy and QR target the same address, and return to the QR.
- [ ] Back out of an unprepared network and verify the displayed network always matches the QR. Return to Arbitrum and verify its original wallet address.
- [ ] With an authorized incoming transfer or an explicitly identified fixture, verify teal receive confetti, Details and OK; Details opens that transfer.
