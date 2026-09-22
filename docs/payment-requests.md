# Incoming Payment Request failures

Bitkit distinguishes Payment Requests rejected while reading a Paykit record from requests that
parse successfully but cannot be opened.

## Failure contract

- Parse-time rejection emits a warning with category `parse`, a stable reason, and only the
  redacted counterparty. It excludes the request id, amount, note, endpoint identifier, and
  endpoint payload.
- Open-time rejection emits a warning with category `resolution` or `presentation`, a stable
  reason, and only the redacted counterparty.
- An explicit Pay action tries immediately and fourteen more times at two-second intervals. After
  the fifteenth failure, Bitkit shows a localized error and leaves the request available for
  another attempt.
- If the request expires during an explicit presentation attempt, Bitkit logs
  `category=presentation reason=request_expired` and shows `PaymentRequestExpiredToast` with the
  localized `wallet__payment_request_expired` message exactly once.
- Automatic presentation uses the same initial retries, then continues every 120 seconds without
  showing terminal feedback.

The parse reasons are `missing_local_role`, `outgoing_request`, `unsupported_local_role`,
`non_actionable_state`, `missing_terms`, `recurring_request`, `unsupported_recurrence`,
`unsupported_asset`, `invalid_amount`, `amount_out_of_range`, `no_supported_endpoint`,
`invalid_expiration`, and `expired`.

The resolution reasons are `no_supported_endpoint`, `endpoint_not_payable`,
`payment_details_pending`, and `resolution_failed`. The presentation reasons are
`invalid_payment_target`, `payment_target_not_routable`, and `request_expired`.

`outgoing_request`, `non_actionable_state`, `recurring_request`, and `expired` are expected filtering
of outgoing, completed, valid subscription, or elapsed records, so they do not emit
incoming-rejection warnings.
`unsupported_recurrence` identifies recurring records that cannot be represented as subscriptions
and emits a privacy-safe warning with only the redacted counterparty.
`unsupported_local_role` identifies an unknown role and emits a privacy-safe warning with only the
redacted counterparty.

## Accessibility identifiers

- Payment Requests screen: `PaymentRequestsScreen`.
- Incoming request row: `PaymentRequestRow-<payment-request-id>`.
- Dismiss action: `PaymentRequestDismiss-<payment-request-id>`.
- Pay action: `PaymentRequestPay-<payment-request-id>`.
- Terminal feedback: `PaymentRequestUnavailableToast`.
- Expiration feedback: `PaymentRequestExpiredToast`.
