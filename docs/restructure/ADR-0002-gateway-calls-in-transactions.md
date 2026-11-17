# ADR 0002: payment gateway calls inside the write transaction

Status: **accepted for the mock gateway**. Revisit before any real gateway is wired in.

Closes wave 3 open item 1 (PLAN.md, "Open items for wave 4").

## Context

SQLite has one writer. `SqliteDb` opens every slice transaction with `BEGIN IMMEDIATE`, so the writer lock is held from the first statement to commit. Four places call `PaymentGateway` while holding it:

| Where | Call | Why it is inside |
|---|---|---|
| `features.booking.Checkout` | `createSession` | the `payment` row stores the session's `gateway_ref` |
| `features.booking.CancelBooking` | `refund` | a failed refund must roll the cancel back |
| `features.payments.ProcessWebhook` | `refund` (late success: booking expired, cancelled, lost its seats) | a failed refund must roll the event back, so the gateway redelivers it |
| `features.admin.CancelShow` | `refund` per confirmed booking | same as cancel |

Today the only gateway is `MockPaymentGateway`. It answers in microseconds, never does I/O, and never fails for a valid amount. Keeping the calls inside the transaction gives a simple, testable rule: either the state change and the money movement both happen, or neither does. `PaymentGatewayContract.aFailedRefundThrowsSoTheCallersTransactionRollsBack` now pins the half of that rule the gateway owns.

## Decision

For the mock gateway, keep the calls inside the transaction. The restructure changes no behaviour, and this is the behaviour every legacy test and the wave 3 and wave 4 equivalence checks were run against.

Do **not** wire a real gateway this way.

## Why it would break with a real gateway

- **The writer lock is held for an HTTP round trip.** A provider call takes 200 ms to several seconds, and its timeout is longer still. Every hold, booking, checkout, webhook and sweep waits behind it. At the busy-timeout they fail with `SQLITE_BUSY`, which users see as 500s or 409s during a sale.
- **The rollback promise is false.** The rollback undoes our rows, not the provider's side. If the provider refunds but the response is lost (timeout, reset), we roll back and record no refund, although the money moved. If the commit fails after a successful call, the result is the same. Retrying then double-refunds, unless the provider dedupes by an idempotency key we never sent.
- **Retries multiply calls.** If Jolt retries a transaction attempt, `SqliteDb` replays the work, gateway call included (handback-booking section 7).
- **Webhook redelivery depends on a 500.** A failed refund now answers the webhook with 500, so the gateway redelivers. That works only while the provider keeps retrying, and it ties our consistency to their retry policy.

## Recommended design: a transactional outbox

1. **Record intent in the transaction.** The state change commits together with a `payment_command` row (`id`, `payment_id`, `kind` = `CREATE_SESSION` or `REFUND`, `amount`, `idempotency_key`, `status` = `PENDING`, `attempts`, `next_attempt_at`, `last_error`). Booking moves to its target status: cancel goes to `REFUNDED` with `refund_amount`. The payment gets a pending state such as `REFUND_PENDING`. The seat events still leave through `Tx.afterCommit`. No gateway I/O happens inside the transaction.
2. **Call the gateway after commit.** A dispatcher runs in `Tx.afterCommit` for low latency, plus a scheduled sweep like `ExpireSweep` for anything missed. It claims `PENDING` rows with a short transaction, then calls the gateway outside any transaction with the row's `idempotency_key`, so a retry can never refund twice. It then records the outcome in a second short transaction: `SENT`, or a backoff with `attempts + 1`, and after N attempts `FAILED` plus an alert.
3. **Reconcile through the webhook.** The provider's `refund.succeeded` and `refund.failed` events (and `payment.succeeded`) settle the command and the payment: `REFUNDED`, or a failed state for support to act on. `payment_event` dedupe stays as it is. Checkout can follow the same pattern: commit the booking as `PAYMENT_PENDING` with a `CREATE_SESSION` command, and either have the client poll for the checkout URL or create the session synchronously **before** opening the write transaction, writing its `gateway_ref` afterwards.

**Consequences.**

- The user-visible contract changes. A cancel answers "refund in progress", not "refunded". SPEC 4 and `api/openapi.yaml` must change first, in their own reviewed PR.
- New schema: a migration for `payment_command` and the new payment states.
- `PaymentGateway.refund` gains an idempotency key, and `PaymentGatewayContract` gains a clause that the same key never refunds twice.
- The rule "a failed refund rolls the state back" is replaced by "a failed refund leaves a visible pending or failed command". `aFailedRefundThrows...` stays, because the dispatcher still needs a failure to be an exception.

## Not done here

No code change. The four call sites keep their current behaviour, and `ProcessWebhook`'s Javadoc points here.
