# Wave 4 hand-back: payments

Branch `feat/slice-payments`, rebased onto `integration` at `50f1988` (wave 5 integrated). Everything below is written against `50f1988`.

| Commit | What |
|---|---|
| `858b00c` | step 0: `ports.GatewayCallback`, `MockGatewaySender` implements it, `WebDeps.mockSender` retyped, bridge in `MockGatewayPayments`, `GatewayCallbackContract` on the real sender |
| `4c821ca` | step 0: `testing.RecordingCallbacks` (held to the contract), exposed as `SliceKit.callbacks()` |
| `0526e79` | open item 2: `PaymentGatewayContract` requires a failed refund to throw (fake, FakeWorld and mock) |
| `f7b8a8c` | the slice, `features/payments`, and its tests |
| `32eb157` | the race: a success webhook against the sweep |
| (this commit) | ADR 0002 (open item 1) and this hand-back |

## 0. Step 0: hot spots touched

These were edited because step 0 is integration-lane work done in this lane. Each edit is minimal.

**Main**

- `ports/GatewayCallback.java`: **new**. `Event(eventId, type, paymentId, gatewayRef, amount, currency, occurredAt)`, the constants `SUCCEEDED` and `FAILED`, and `int send(Event) throws Exception`.
- `adapters/payment/MockGatewaySender.java`: implements `GatewayCallback`. `toWire` copies fields, because `type` is already the wire string. It no longer imports `application`.
- `web/WebDeps.java`: `MockGatewayPayments.Sender mockSender` becomes `GatewayCallback mockSender` (one import swapped).
- `application/MockGatewayPayments.java`: the bridge. A second constructor `(UnitOfWork, GatewayCallback, Ids, Clock)` converts the legacy event, and `Sender extends GatewayCallback` with an unused default method.
  - It is more than one line on purpose: `WebModules` and the implicitly typed lambdas `e -> 401` in `AdminReportsAndQueriesTest` both compile unchanged.
  - The subtyping makes the `Sender` constructor the most specific for a lambda, and a `GatewayCallback` value picks the bridge.
  - **`WebModules` is not touched.**

**Tests**

- `web/WebKit.java`: `sentWebhooks` is `List<GatewayCallback.Event>`.
- `web/BookingApiTest.java`: one assertion compares `"payment.succeeded"`.
- `adapters/payment/PaymentAdaptersTest.java`: builds `GatewayCallback.Event`.
- `testing/SliceKit.java` (`callbacks()`) and `SliceKitTest` (one more assertion).
- `contract/PaymentGatewayContract.java` and its three implementations (`RecordingGatewayContractTest`, `FakeWorldPaymentGatewayContractTest`, `MockPaymentGatewayContractTest`): the refund-failure clause.
- **New:** `contract/GatewayCallbackContract`, `adapters/payment/MockGatewaySenderContractTest` (a real HTTP stub; an unreachable port for the failure case), `testing/RecordingCallbacks` and `RecordingCallbacksContractTest`.

## 1. Swap lines (against `50f1988`)

`WebModules.routes`: replace the `MockGatewayPayments mock = ...` statement and the `pipeline.module(new PaymentModule(...))` block with:

```java
        pipeline.module(
                new PaymentRoutes(
                        deps.db(),
                        deps.gateway(),
                        deps.publisher(),
                        deps.ids(),
                        deps.clock(),
                        Webhooks.hmacSha256(settings.webhookSecret(), "Bms-Signature")
                                .prefix("v1=")
                                .timestampHeader("Bms-Timestamp")
                                .tolerance(WEBHOOK_TOLERANCE)
                                .clock(deps.clock()),
                        settings.mockGateway() ? deps.mockSender() : null,
                        mapper));
```

Imports: remove `application.MockGatewayPayments` and `application.ProcessPaymentWebhook`, and add `features.payments.PaymentRoutes`.

`Main`: no change. `new MockGatewaySender(...)` is already a `GatewayCallback`.

## 2. Deletions

Every deletion below was tried on the scratch branch, and the full verify passed (section 4).

**Main** (799 lines):

- `web/PaymentModule` (161)
- `application/ProcessPaymentWebhook` (153)
- `application/MockGatewayPayments`, with the step 0 bridge (109)
- the remaining fixtures, all deletable now:
  - `CancelShow` (78), whose last user was `ProcessPaymentWebhookTest`
  - `CreateBooking` (99), `Checkout` (82), `Outcome` (28)
  - `BookingOps` (50), `EventBuffer` (39)

**Tests** (811 lines):

- `application/ProcessPaymentWebhookTest` (165)
- `web/BookingApiTest`, the whole file (367)
- `application/AdminReportsAndQueriesTest`: since wave 5 it holds only the mock gateway case, so delete the whole file (65)
- `application/CheckoutTest` (121) and `CreateBookingTest` (93)

**Keep:**

- `application/package-info.java`. Deleting it fails `ArchitectureTest.applicationNeverImportsAdaptersWebOrInfrastructure`, which expects the package to exist (tried). Drop both in wave 6.
- `WebKit.sentWebhooks` now has no reader. It is harmless, and `WebKit` goes in wave 6.
- **Fixture classes:** with the deletions above, `application` holds no class, only `package-info`. `application.fakes.FakeWorld` is still used by `WebKit` and its contract tests (wave 6).

## 3. `WEB_RECORDS` (replace)

- `WebhookEvent -> dev.thiru.bms.features.payments.PaymentDtos$WebhookBody`
- `CompleteRequest -> dev.thiru.bms.features.payments.PaymentDtos$CompleteRequest`

## 4. Verify totals

- **This branch, without the swap:** `mvn -B verify` BUILD SUCCESS, **657 tests**, 0 failures, 0 errors, 0 skipped. That is 629 after step 0, plus 28 slice tests. `integration` at `50f1988` has 620 tests.
- **Swap proof: BUILD SUCCESS, 623 tests**, 0 failures, 0 errors, 0 skipped, with `spotless:check` and jacoco. The scratch branch was cut from `origin/integration` (`50f1988`, which already contains `feat/restructure-wave5`) with this branch merged in. It had the swap from section 1, `WEB_RECORDS` from section 3 and every deletion from section 2. The scratch branch and its worktree were then deleted.
- Green in that run: `EndToEndTest` 2 (the payment journey goes over a real socket through `MockGatewaySender` into `PaymentRoutes`), `MainTest` 12, `RbacTest` 13, `RbacMatrixTest` 3, `ApiSchemaTest` (no schema edit), `ApiCoverageTest` 5, `RecordGeneratorTest` 4, `AdminRoutesTest` 14, `OwnerRoutesTest` 8, `ArchitectureTest` 5, `TargetArchitectureTest` 13, `SqliteDbContractTest` 12, every `PaymentGatewayContract`, `GatewayCallbackContract` and `SeatEventPublisherContract` suite, and the 28 payments tests.
- **Wave 5 note.** When this lane started, wave 5 was only a local branch. It was integrated as `50f1988` before the proof, so the single scratch run covers both.

**Equivalence check** (scratch, not committed). A test in `dev.thiru.bms.web` mounted the legacy `PaymentModule` (with `ProcessPaymentWebhook` and `MockGatewayPayments` through the bridge, on the legacy unit of work) and `PaymentRoutes` on two `SliceKit`s from the same V2 template. Both had `BookingRoutes` beside them, the same sequential ids, gateway fake and clock moves, and a mock callback that signs each event and posts it to the kit's own webhook. The callback could also be set to answer 401 or to be unreachable.

**Mock gateway on: 102 requests.**

- **Signatures:** forged, no headers, no signature, no timestamp, tampered body, a bad prefix, ±301 s (401), and ±300 s (reaches validation).
- **Validation:** junk, `{}`, `null`, blank fields, wrong JSON types, an unknown `type`.
- **Unknown payment:** 404.
- **Mismatches:** an amount mismatch, its replay, and a currency mismatch.
- **Success path:** success, a duplicate, a duplicate with a different body, a failure after success, a second success, and a late success on an already failed attempt.
- **Ticket:** served after payment.
- **Checkout limit:** three failures, then 429.
- **After expiry:** a late success (refund and `Released(EXPIRED)`), its duplicate and a second id; a late failure; a late mismatch.
- **Lost seats:** a lost lock and a detached lock, each expiring the booking with a refund.
- **Cancelled booking:** success refunds; failure is a no-op.
- **Failing refund:** 500 and rollback, then the redelivery is processed.
- **Mock gateway page:** found, unknown, and after payment.
- **Completion:**
  - form SUCCESS (end to end to `CONFIRMED`) and JSON FAILURE on a paid payment
  - 422 for `MAYBE`, JSON `null`, an empty form and no content type
  - 403 for a cross-site post, 404 for an unknown payment
  - FAILURE back to `HELD`
  - a rejected (401) and an unreachable callback, each 500
  - completion after expiry

**Mock gateway off: 6 requests.** Both mock routes are 404, and the webhook still works.

**Result:**

- **0 differences** in status, body (only `requestId` normalised) and 16 headers.
- The 20 seat events are identical and in the same order.
- The 6 gateway refunds and the 6 delivered callback events (ids included) are identical.
- The 144 final rows of `booking`, `booking_seat`, `payment`, `payment_event`, `seat_lock` and `seat_hold` are identical.

## 5. Lines of code

| | Legacy (deleted at step 3) | Slice |
|---|---|---|
| Routes | `PaymentModule` 161 | `PaymentRoutes` 161 |
| Use cases | `ProcessPaymentWebhook` 153, `MockGatewayPayments` 109 | `ProcessWebhook` 148, `MockGateway` 73 |
| Shared steps | `BookingOps` 50, `EventBuffer` 39 | `BookingOps` 56 (with after-commit publishing) |
| SQL | in `SqlBookings` and `SqlSeatLocks` (stay until wave 6) | `PaymentSql` 169 |
| Records | nested in `PaymentModule` | `PaymentDtos` 25 |
| Fixtures also deleted | `CancelShow` 78, `CreateBooking` 99, `Checkout` 82, `Outcome` 28 | none |
| **Main** | **799** | **632** |
| Tests | 811 (`ProcessPaymentWebhookTest`, `BookingApiTest`, `AdminReportsAndQueriesTest`, `CheckoutTest`, `CreateBookingTest`) | 1015 (with the 292-line `PaymentFlow` harness) |

**Size** (`src/main/java` and `src/test/java`, files / lines):

| | `50f1988` | This branch | After step 3 (scratch) |
|---|---:|---:|---:|
| main | 126 / 12 864 | 133 / 13 554 | 123 / 12 747 |
| test | 117 / 18 191 | 126 / 19 468 | 121 / 18 657 |
| tests run | 620 | 657 | 623 |

Step 0 adds 34 main lines (the port, plus about 30 changed in the adapter, `WebDeps` and the bridge) and about 290 test lines (the callback contract, its two implementations and the gateway-contract clause). Every slice commit touched only `features/payments`.

## 6. Open items, settled

1. **Gateway calls inside the IMMEDIATE transaction.** Kept for the mock gateway, with no behaviour change. [ADR 0002](ADR-0002-gateway-calls-in-transactions.md) records the risk with a real gateway: the writer lock is held for an HTTP round trip, the rollback is false when a response is lost, and a retry would refund twice. It recommends an outbox: record intent in the transaction, call the gateway after commit with an idempotency key, and reconcile through the webhook. No code changed.
2. **No contract covered a refund failure.** Closed by `0526e79`. The fakes are made to fail through their outage switch. `MockPaymentGateway` has no outage mode, so the clause is met by a refund it rejects. That is recorded in its contract test.

**Suggested PLAN.md text** for the integration lane's wave 4 record: "Open item 1: accepted for the mock gateway, see ADR 0002 (outbox before any real gateway). Open item 2: `PaymentGatewayContract.aFailedRefundThrowsSoTheCallersTransactionRollsBack`; the mock is held to it through a rejected amount."

## 7. Notes

- **Behaviour.** No change found. The equivalence script, the legacy `BookingApiTest` (still green on this branch, through the bridge), both `EndToEndTest` journeys and the race all agree.
- **The race.** `WebhookSweepRaceTest` runs 10 rounds on virtual threads, with the sweeper's clock 8 minutes ahead.
  - Runs split about half `CONFIRMED` and half `EXPIRED` with a full refund (5/5 and 4/6 seen).
  - A rival's re-hold gets 409 or 201 to match.
  - No seat is ever locked twice.
- **Port behaviour no contract covers:** none new. The slice relies on the refund-failure clause (now covered) and on `Db`'s after-commit and rollback rules (`DbContract`).
- **One flaky run, unrelated.** One full verify failed in `adapters.db.AdminRepositoriesTest` and `CatalogRepositoriesTest`, with "migration failed: cannot commit - no transaction is active" in `SchemaRunner` under memory pressure. The next run passed. It is a legacy `DbTest` path, untouched here.
